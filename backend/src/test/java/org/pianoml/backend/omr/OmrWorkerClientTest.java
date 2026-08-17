package org.pianoml.backend.omr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Tests for the OMR worker client.
 *
 * <p>The behaviours pinned here are the ones whose absence broke the legacy
 * {@code CloudRunJobService}: the score id must actually travel with the request, and a
 * failure must reach the caller rather than vanish into a callback (AUDIT §R2).
 */
class OmrWorkerClientTest {

  private static final String BASE_URL = "http://worker:8000";

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private OmrWorkerClient client;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new OmrWorkerClient(builder.build(), "");
  }

  @AfterEach
  void tearDown() {
    server.verify();
  }

  private OmrSubmitRequest request() {
    return new OmrSubmitRequest("score-123", "raw/score-123/original.pdf", "Clair de Lune", "Debussy", false);
  }

  @Test
  @DisplayName("submission carries the score id to the worker")
  void submissionCarriesScoreId() {
    // The exact defect in CloudRunJobService: it accepted scoreId and s3Key and then
    // built a request that contained neither.
    server.expect(requestTo(BASE_URL + "/api/v1/omr/process"))
        .andExpect(method(org.springframework.http.HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("score-123")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("raw/score-123/original.pdf")))
        .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"jobId":"job_abc","scoreId":"score-123","status":"QUEUED","deduplicated":false}
                """));

    OmrSubmission submission = client.submitByKey(request());

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.jobId()).isEqualTo("job_abc");
    assertThat(submission.jobIdIfAccepted()).contains("job_abc");
  }

  @Test
  @DisplayName("deduplicated submission is reported, not treated as new work")
  void reportsDeduplication() {
    server.expect(requestTo(BASE_URL + "/api/v1/omr/process"))
        .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"jobId":"job_existing","scoreId":"score-123","status":"RUNNING","deduplicated":true}
                """));

    OmrSubmission submission = client.submitByKey(request());

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.deduplicated()).isTrue();
  }

  @Test
  @DisplayName("a 4xx rejection reaches the caller with the worker's reason intact")
  void rejectionSurfacesReason() {
    server.expect(requestTo(BASE_URL + "/api/v1/omr/process"))
        .andRespond(withStatus(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"detail":{"code":"FILE_TOO_LARGE","message":"file exceeds 50 MB"}}
                """));

    OmrSubmission submission = client.submitByKey(request());

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.errorCode()).isEqualTo("WORKER_REJECTED");
    // The specific reason must survive: "FILE_TOO_LARGE" is actionable, "500" is not.
    assertThat(submission.errorDetail()).contains("FILE_TOO_LARGE");
    assertThat(submission.isRetryable()).isFalse();
  }

  @Test
  @DisplayName("a 5xx failure is reported as retryable-by-rejection, not as success")
  void serverErrorIsNotSuccess() {
    server.expect(requestTo(BASE_URL + "/api/v1/omr/process"))
        .andRespond(withServerError().body("boom"));

    OmrSubmission submission = client.submitByKey(request());

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.jobIdIfAccepted()).isEmpty();
  }

  @Test
  @DisplayName("job status exposes the page reconciliation")
  void jobStatusExposesPageReconciliation() {
    server.expect(requestTo(BASE_URL + "/api/v1/omr/jobs/job_abc"))
        .andRespond(withSuccess("""
            {
              "jobId":"job_abc","scoreId":"score-123","status":"REVIEW_REQUIRED",
              "stage":"VALIDATE","progress":0.75,"message":"partial","attempt":1,
              "createdAt":"2026-08-17T10:00:00Z","updatedAt":"2026-08-17T10:05:00Z",
              "pages":{"sourcePages":12,"recognisedPages":9,"droppedPages":[7,8,9],"coverage":0.75},
              "errorCode":"PAGE_DROPPED","errorDetail":"3 of 12 pages were not recognised"
            }
            """, MediaType.APPLICATION_JSON));

    OmrJobStatus status = client.getJobStatus("job_abc").orElseThrow();

    assertThat(status.isTerminal()).isTrue();
    assertThat(status.isSuccessful()).isFalse();
    assertThat(status.requiresReview()).isTrue();
    assertThat(status.pages().droppedPages()).containsExactly(7, 8, 9);
    assertThat(status.pages().hasDroppedPages()).isTrue();
  }

  @Test
  @DisplayName("dropped pages force review even when the status claims completion")
  void droppedPagesForceReviewRegardlessOfStatus() {
    server.expect(requestTo(BASE_URL + "/api/v1/omr/jobs/job_x"))
        .andRespond(withSuccess("""
            {
              "jobId":"job_x","scoreId":"s","status":"COMPLETED","stage":"ANALYSE",
              "progress":1.0,"message":"done","attempt":1,
              "createdAt":"2026-08-17T10:00:00Z","updatedAt":"2026-08-17T10:05:00Z",
              "pages":{"sourcePages":10,"recognisedPages":8,"droppedPages":[3,4],"coverage":0.8}
            }
            """, MediaType.APPLICATION_JSON));

    OmrJobStatus status = client.getJobStatus("job_x").orElseThrow();

    assertThat(status.isSuccessful()).isTrue();
    assertThat(status.requiresReview())
        .as("a COMPLETED job that dropped pages must still be flagged")
        .isTrue();
  }

  @Test
  @DisplayName("unknown job id yields an empty optional, not an exception")
  void unknownJobIsEmpty() {
    server.expect(requestTo(BASE_URL + "/api/v1/omr/jobs/nope"))
        .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

    assertThat(client.getJobStatus("nope")).isEmpty();
  }

  @Test
  @DisplayName("document fetch returns raw JSON so it is stored without re-serialising")
  void fetchesDocumentAsRawJson() {
    String document = "{\"score_id\":\"s\",\"revision\":1,\"schema_version\":\"1.0\"}";
    server.expect(requestTo(BASE_URL + "/api/v1/scores/score-123/document"))
        .andRespond(withSuccess(document, MediaType.APPLICATION_JSON));

    assertThat(client.fetchDocument("score-123", null)).contains(document);
  }

  @Test
  @DisplayName("missing document yields an empty optional")
  void missingDocumentIsEmpty() {
    server.expect(requestTo(BASE_URL + "/api/v1/scores/nope/document"))
        .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

    assertThat(client.fetchDocument("nope", null)).isEmpty();
  }

  @Test
  @DisplayName("health reports ready only when the worker says ok")
  void healthReflectsWorkerReadiness() {
    server.expect(requestTo(BASE_URL + "/health"))
        .andRespond(withSuccess("""
            {"status":"ok","version":"0.1.0","checks":{"musescore3":true}}
            """, MediaType.APPLICATION_JSON));

    assertThat(client.isHealthy()).isTrue();
  }

  @Test
  @DisplayName("degraded worker is not healthy")
  void degradedWorkerIsNotHealthy() {
    server.expect(requestTo(BASE_URL + "/health"))
        .andRespond(withSuccess("""
            {"status":"degraded","version":"0.1.0","checks":{"musescore3":false}}
            """, MediaType.APPLICATION_JSON));

    assertThat(client.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("request validation rejects a blank score id before any network call")
  void rejectsBlankScoreId() {
    org.assertj.core.api.Assertions
        .assertThatThrownBy(() -> new OmrSubmitRequest("", "k", "t", "c", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scoreId");
  }

  @Test
  @DisplayName("blank composer defaults rather than reaching the worker empty")
  void blankComposerDefaults() {
    assertThat(new OmrSubmitRequest("s", "k", "t", "  ", false).composer()).isEqualTo("Unknown");
  }
}
