package org.pianoml.backend.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.omr.OmrSubmission;
import org.pianoml.backend.omr.OmrSubmitRequest;
import org.pianoml.backend.omr.OmrWorkerClient;
import org.pianoml.backend.repository.ScoreRepository;
import org.pianoml.backend.storage.ScoreStorageException;
import org.pianoml.backend.storage.ScoreStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Accepts a sheet-music upload and starts ingestion.
 *
 * <p>Composes services that already exist and are covered by tests: storage
 * ({@link ScoreStorageService}), job bookkeeping ({@link ScoreIngestionService}) and the
 * worker handoff ({@link OmrWorkerClient}). The controller's own job is validation, the
 * score record, and returning 202 quickly — recognition takes tens of seconds and must
 * never be done inside the request.
 */
@RestController
@RequestMapping("/api/v1/scores")
@RequiredArgsConstructor
@Slf4j
public class ScoreUploadController {

  private final ScoreStorageService storageService;
  private final ScoreIngestionService ingestionService;
  private final OmrWorkerClient workerClient;
  private final ScoreRepository scoreRepository;
  private final OwnerScopeResolver ownerResolver;
  private final AuthorResolver authorResolver;

  /** 50 MB, matching the worker's own limit so a file cannot pass here and fail there. */
  static final long MAX_BYTES = 50L * 1024 * 1024;

  static final Set<String> ALLOWED_EXTENSIONS =
      Set.of(".pdf", ".musicxml", ".xml", ".mxl", ".mid", ".midi");

  /**
   * Content types we accept.
   *
   * <p>Extension is the primary check: browsers disagree wildly on the type they attach
   * to .musicxml and .mxl, and several send {@code application/octet-stream} for all of
   * them. This set exists to reject an obviously wrong type, not to be authoritative.
   */
  private static final Set<String> REJECTED_TYPE_PREFIXES =
      Set.of("image/", "video/", "audio/", "text/html");

  /**
   * Deliberately NOT {@code @Transactional}.
   *
   * <p>Two of the three steps here — writing to object storage and handing the job to
   * the worker — are not transactional and cannot be rolled back. Wrapping the method
   * only rolled back the third, so a worker outage discarded the score row while leaving
   * the uploaded file orphaned in storage, and the "mark FAILED" write below never
   * survived. Each write now commits on its own, which is what actually happens anyway.
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<UploadAcceptedResponse> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "title", required = false) String title,
      @RequestParam(value = "composer", required = false) String composer,
      @RequestParam(value = "makeFingering", defaultValue = "false") boolean makeFingering,
      @RequestParam(value = "guestSessionId", required = false) String guestSessionId) {

    String extension = validate(file);

    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      log.error("could not read the uploaded part", e);
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The uploaded file could not be read.");
    }

    OwnerScope owner = ownerResolver.resolve(guestSessionId);
    Score score = createScore(file, title, composer, owner);

    // Store the original BEFORE submitting. If the worker call fails we still hold the
    // upload and the job can be retried; the reverse would lose the file.
    try {
      storageService.putRaw(score.getId(), extension, content, file.getContentType());
    } catch (ScoreStorageException e) {
      log.error("storage rejected the upload for score {}", score.getId(), e);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Storage is unavailable, so the file could not be saved. Please try again.");
    }

    OmrSubmission submission =
        workerClient.submitBytes(
            new OmrSubmitRequest(
                score.getId().toString(),
                ScoreStorageService.rawKey(score.getId(), extension),
                score.getTitle(),
                composer,
                makeFingering),
            content,
            "upload" + extension);

    if (!submission.accepted()) {
      score.setProcessingStatus(ScoreIngestionService.STATUS_FAILED);
      scoreRepository.save(score);

      // The worker being down is transient and worth retrying; a rejection is not.
      HttpStatus status =
          submission.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
      log.error(
          "worker did not accept score {}: {} — {}",
          score.getId(), submission.errorCode(), submission.errorDetail());
      throw new ResponseStatusException(
          status,
          submission.isRetryable()
              ? "The transcription service is starting up. Please try again in a moment."
              : "The transcription service rejected this file: " + submission.errorDetail());
    }

    String jobId = submission.jobId();
    ingestionService.markQueued(score.getId(), jobId);

    log.info(
        "accepted upload '{}' as score {} (job {}, {} bytes)",
        file.getOriginalFilename(), score.getId(), jobId, content.length);

    return ResponseEntity.accepted()
        .body(new UploadAcceptedResponse(
            score.getId().toString(), jobId, ScoreIngestionService.STATUS_QUEUED,
            score.getGuestSessionId()));
  }

  /** @return the normalised file extension, including the dot. */
  private String validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "That file is " + (file.getSize() / (1024 * 1024)) + " MB. The limit is 50 MB.");
    }

    String name = file.getOriginalFilename();
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file has no name.");
    }

    String lower = name.toLowerCase(Locale.ROOT);
    String extension =
        ALLOWED_EXTENSIONS.stream().filter(lower::endsWith).findFirst().orElse(null);

    if (extension == null) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "Unsupported file type. Use a PDF, MusicXML or MIDI file.");
    }

    String contentType = file.getContentType();
    if (contentType != null
        && REJECTED_TYPE_PREFIXES.stream().anyMatch(contentType::startsWith)) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "That looks like a " + contentType + " file, not sheet music.");
    }

    return extension;
  }

  private Score createScore(
      MultipartFile file, String title, String composer, OwnerScope owner) {
    Score score = new Score();
    score.setTitle(resolveTitle(title, file.getOriginalFilename()));
    score.setOwner(owner.user());
    score.setGuestSessionId(owner.guestSessionId());
    // score.author_id is NOT NULL; an unnamed composer resolves to a shared
    // "Unknown" author rather than failing the upload.
    score.setAuthor(authorResolver.resolve(composer));
    score.setVersion(1);
    score.setHasFiles(false);
    score.setProcessingStatus(ScoreIngestionService.STATUS_QUEUED);
    // Uploads are private until the owner says otherwise (AUDIT §R5).
    score.setRights("USER_UPLOAD_PRIVATE");
    score.setPublicDomain(false);
    score.setUpdatedAt(OffsetDateTime.now());
    return scoreRepository.save(score);
  }

  private String resolveTitle(String title, String filename) {
    if (title != null && !title.isBlank()) {
      return title.trim();
    }
    if (filename == null || filename.isBlank()) {
      return "Untitled score";
    }
    String stem = filename.contains(".")
        ? filename.substring(0, filename.lastIndexOf('.'))
        : filename;
    return stem.isBlank() ? "Untitled score" : stem;
  }

}
