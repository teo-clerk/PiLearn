package org.pianoml.backend.omr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Optional;

/**
 * Client for the FastAPI OMR worker.
 *
 * <p>Replaces {@code CloudRunJobService}, which had three defects that made ingestion
 * unreliable (AUDIT §R2):
 *
 * <ol>
 *   <li>It built {@code RunJobRequest} with only a job name, so the {@code scoreId} and
 *       {@code s3Key} it was handed were never actually passed to the job — they were
 *       only logged.</li>
 *   <li>The job compensated by draining every PENDING row, so two concurrent triggers
 *       processed the same score twice.</li>
 *   <li>Failures were swallowed inside a {@code whenComplete} callback on a
 *       {@code CompletableFuture}, so a synchronous caller saw success regardless.</li>
 * </ol>
 *
 * <p>Here the score id travels in the request, the worker claims the job atomically, and
 * failures are returned to the caller as a typed result rather than lost in a callback.
 *
 * <p>Non-blocking is achieved by the worker returning 202 immediately — the HTTP call
 * itself is short. {@code RestClient} is used rather than {@code WebClient} because the
 * rest of this codebase is servlet-stack and blocking; introducing a reactive client for
 * one short call would add Reactor to the dependency graph for no benefit.
 */
@Component
@Slf4j
public class OmrWorkerClient {

  private final RestClient restClient;
  private final String workerToken;

  /**
   * Marked explicitly because this class has two constructors (the second is for
   * tests). With neither annotated, Spring stops looking for an injectable candidate
   * and falls back to a no-arg constructor that does not exist, failing every
   * @SpringBootTest context load with NoSuchMethodException.
   */
  @Autowired
  public OmrWorkerClient(
      @Value("${omr.worker.url:http://localhost:8000}") String baseUrl,
      @Value("${omr.worker.token:}") String workerToken,
      @Value("${omr.worker.connect-timeout-ms:5000}") int connectTimeoutMs,
      @Value("${omr.worker.read-timeout-ms:30000}") int readTimeoutMs) {

    this.workerToken = workerToken;
    this.restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
        .build();

    log.info("OMR worker client targeting {}", baseUrl);
  }

  /** Package-private constructor for tests, which supply their own RestClient. */
  OmrWorkerClient(RestClient restClient, String workerToken) {
    this.restClient = restClient;
    this.workerToken = workerToken;
  }

  private static ClientHttpRequestFactory requestFactory(int connectMs, int readMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectMs));
    factory.setReadTimeout(Duration.ofMillis(readMs));
    return factory;
  }

  /**
   * Submit a score already staged in object storage.
   *
   * @return the accepted job, or a failure describing why submission did not happen.
   */
  public OmrSubmission submitByKey(OmrSubmitRequest request) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("scoreId", request.scoreId());
    form.add("s3Key", request.storageKey());
    form.add("title", request.title());
    form.add("composer", request.composer());
    form.add("makeFingering", String.valueOf(request.makeFingering()));

    return post(form, request.scoreId());
  }

  /** Submit raw bytes directly, bypassing object storage. */
  public OmrSubmission submitBytes(OmrSubmitRequest request, byte[] content, String filename) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("scoreId", request.scoreId());
    form.add("title", request.title());
    form.add("composer", request.composer());
    form.add("makeFingering", String.valueOf(request.makeFingering()));
    form.add("file", namedResource(content, filename));

    return post(form, request.scoreId());
  }

  private Resource namedResource(byte[] content, String filename) {
    return new ByteArrayResource(content) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }

  private OmrSubmission post(MultiValueMap<String, Object> form, String scoreId) {
    try {
      OmrSubmitResponse response = restClient.post()
          .uri("/api/v1/omr/process")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .headers(headers -> {
            if (!workerToken.isBlank()) {
              headers.setBearerAuth(workerToken);
            }
          })
          .body(form)
          .exchange((req, res) -> {
            HttpStatusCode statusCode = res.getStatusCode();
            if (statusCode.is2xxSuccessful()) {
              return res.bodyTo(OmrSubmitResponse.class);
            }
            // Read the body before throwing: the worker's error payload names the
            // specific rejection (FILE_TOO_LARGE, NOT_A_PDF, ...) and losing it turns
            // an actionable message into "500".
            String body = new String(res.getBody().readAllBytes());
            throw new OmrWorkerException(
                "worker rejected submission with " + statusCode.value() + ": " + body,
                statusCode.value());
          });

      if (response == null) {
        return OmrSubmission.failure(scoreId, "EMPTY_RESPONSE", "worker returned no body");
      }

      log.info("OMR job {} accepted for score {} (deduplicated={})",
          response.jobId(), scoreId, response.deduplicated());
      return OmrSubmission.accepted(response);

    } catch (ResourceAccessException e) {
      // Network-level: the worker is down or unreachable. Retryable.
      log.error("OMR worker unreachable for score {}", scoreId, e);
      return OmrSubmission.failure(scoreId, "WORKER_UNREACHABLE", e.getMessage());

    } catch (OmrWorkerException e) {
      log.error("OMR worker rejected score {}: {}", scoreId, e.getMessage());
      return OmrSubmission.failure(scoreId, "WORKER_REJECTED", e.getMessage());

    } catch (Exception e) {
      log.error("Unexpected error submitting score {} to the OMR worker", scoreId, e);
      return OmrSubmission.failure(scoreId, "SUBMIT_FAILED", e.toString());
    }
  }

  /** Poll a job's status. Empty when the worker does not know the job. */
  public Optional<OmrJobStatus> getJobStatus(String jobId) {
    try {
      return Optional.ofNullable(
          restClient.get()
              .uri("/api/v1/omr/jobs/{jobId}", jobId)
              .headers(headers -> {
                if (!workerToken.isBlank()) {
                  headers.setBearerAuth(workerToken);
                }
              })
              .exchange((req, res) -> {
                if (res.getStatusCode().value() == 404) {
                  return null;
                }
                if (!res.getStatusCode().is2xxSuccessful()) {
                  throw new OmrWorkerException(
                      "job status query failed: " + res.getStatusCode(),
                      res.getStatusCode().value());
                }
                return res.bodyTo(OmrJobStatus.class);
              }));
    } catch (Exception e) {
      log.warn("Could not fetch status for job {}: {}", jobId, e.getMessage());
      return Optional.empty();
    }
  }

  /** Fetch the canonical ScoreDocument as raw JSON, for storage without re-serialising. */
  public Optional<String> fetchDocument(String scoreId, Integer revision) {
    try {
      return Optional.ofNullable(
          restClient.get()
              .uri(uriBuilder -> {
                var builder = uriBuilder.path("/api/v1/scores/{scoreId}/document");
                if (revision != null) {
                  builder.queryParam("revision", revision);
                }
                return builder.build(scoreId);
              })
              .headers(headers -> {
                if (!workerToken.isBlank()) {
                  headers.setBearerAuth(workerToken);
                }
              })
              .exchange((req, res) -> {
                if (res.getStatusCode().value() == 404) {
                  return null;
                }
                if (!res.getStatusCode().is2xxSuccessful()) {
                  throw new OmrWorkerException(
                      "document fetch failed: " + res.getStatusCode(),
                      res.getStatusCode().value());
                }
                return new String(res.getBody().readAllBytes());
              }));
    } catch (Exception e) {
      log.warn("Could not fetch document for score {}: {}", scoreId, e.getMessage());
      return Optional.empty();
    }
  }

  /** True when the worker is reachable and reports itself ready. */
  public boolean isHealthy() {
    try {
      OmrHealth health = restClient.get()
          .uri("/health")
          .retrieve()
          .body(OmrHealth.class);
      return health != null && "ok".equals(health.status());
    } catch (Exception e) {
      log.debug("OMR worker health check failed: {}", e.getMessage());
      return false;
    }
  }
}
