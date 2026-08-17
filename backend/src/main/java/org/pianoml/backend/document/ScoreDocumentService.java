package org.pianoml.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists and serves the canonical ScoreDocument.
 *
 * <p>Two invariants this service enforces, both mirroring guarantees the worker's Pydantic
 * model already makes — repeated here because the database is the last line of defence and
 * a future caller may bypass the worker:
 *
 * <ol>
 *   <li><b>Revisions are immutable.</b> Saving never updates an existing (scoreId,
 *       revision); it allocates the next one. An in-flight learner's plan must keep
 *       resolving against the document it was built from.</li>
 *   <li><b>A document with dropped pages cannot claim to be OK.</b> This is the legacy
 *       silent-partial-failure defect (AUDIT §R7). Rejected at write time.</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScoreDocumentService {

  private final ScoreDocumentRepository repository;
  private final ObjectMapper objectMapper;

  /**
   * Store a new revision of a score's document.
   *
   * @param scoreId the score
   * @param documentJson the full ScoreDocument as emitted by the worker
   * @param alignmentJson the compact alignment index, or null
   * @return the persisted entity, with its allocated revision
   * @throws InvalidScoreDocumentException if the payload is malformed or self-contradictory
   */
  @Transactional
  public ScoreDocumentEntity save(UUID scoreId, String documentJson, String alignmentJson) {
    JsonNode root = parse(documentJson);

    ScoreDocumentEntity entity = new ScoreDocumentEntity();
    entity.setScoreId(scoreId);
    entity.setRevision(nextRevision(scoreId));
    entity.setDocument(documentJson);
    entity.setAlignmentIndex(alignmentJson);

    entity.setSchemaVersion(requiredText(root, "schema_version"));
    entity.setAnalysisVersion(requiredText(root, "analysis_version"));

    JsonNode confidence = root.path("confidence");
    if (confidence.isMissingNode() || confidence.isNull()) {
      throw new InvalidScoreDocumentException(
          "document has no confidence report; a document that cannot state how well it "
              + "was recognised must not be persisted");
    }
    entity.setConfidence(confidence.toString());
    entity.setReviewStatus(confidence.path("status").asText("REVIEW_REQUIRED"));
    entity.setDocumentConfidence(
        confidence.path("document_confidence").isNumber()
            ? confidence.path("document_confidence").asDouble()
            : null);

    PageCounts pages = countPages(confidence);
    entity.setSourcePages(pages.total());
    entity.setRecognisedPages(pages.recognised());

    JsonNode meta = root.path("meta");
    if (meta.path("measure_count").isNumber()) {
      entity.setMeasureCount(meta.path("measure_count").asInt());
    }
    JsonNode difficulty = root.path("difficulty");
    if (difficulty.path("global_grade").isNumber()) {
      entity.setGlobalGrade(difficulty.path("global_grade").asDouble());
    }

    validateConsistency(entity, pages);

    ScoreDocumentEntity saved = repository.save(entity);
    log.info(
        "stored ScoreDocument for score {} revision {} (status={}, confidence={}, pages={}/{})",
        scoreId, saved.getRevision(), saved.getReviewStatus(),
        saved.getDocumentConfidence(), saved.getRecognisedPages(), saved.getSourcePages());
    return saved;
  }

  /**
   * The write-time guard against the legacy defect.
   *
   * <p>{@code pdf2pack.sh} exited 0 whenever at least one page succeeded, so a 12-page
   * score that recognised 9 pages was reported as a complete success. Any document that
   * claims OK while carrying dropped pages is rejected here.
   */
  private void validateConsistency(ScoreDocumentEntity entity, PageCounts pages) {
    boolean claimsOk = "OK".equals(entity.getReviewStatus());
    boolean droppedPages = pages.total() != null
        && pages.recognised() != null
        && pages.recognised() < pages.total();

    if (claimsOk && droppedPages) {
      throw new InvalidScoreDocumentException(
          String.format(
              "document claims status OK but only %d of %d pages were recognised. "
                  + "Missing pages mean missing measures; this must be REVIEW_REQUIRED.",
              pages.recognised(), pages.total()));
    }

    if (entity.getMeasureCount() != null && entity.getMeasureCount() <= 0) {
      throw new InvalidScoreDocumentException(
          "document reports " + entity.getMeasureCount() + " measures; a score with no "
              + "measures cannot drive practice");
    }
  }

  private record PageCounts(Integer total, Integer recognised) {}

  private PageCounts countPages(JsonNode confidence) {
    JsonNode pages = confidence.path("pages");
    if (!pages.isArray() || pages.isEmpty()) {
      return new PageCounts(null, null);
    }
    int total = pages.size();
    int recognised = 0;
    for (JsonNode page : pages) {
      if (page.path("recognised").asBoolean(false)) {
        recognised++;
      }
    }
    return new PageCounts(total, recognised);
  }

  @Transactional(readOnly = true)
  public Optional<ScoreDocumentEntity> findLatest(UUID scoreId) {
    return repository.findLatestByScoreId(scoreId);
  }

  @Transactional(readOnly = true)
  public Optional<ScoreDocumentEntity> find(UUID scoreId, Integer revision) {
    return revision == null
        ? repository.findLatestByScoreId(scoreId)
        : repository.findByScoreIdAndRevision(scoreId, revision);
  }

  /** The raw document JSON, for serving straight to a client without re-serialising. */
  @Transactional(readOnly = true)
  public Optional<String> findDocumentJson(UUID scoreId, Integer revision) {
    return find(scoreId, revision).map(ScoreDocumentEntity::getDocument);
  }

  @Transactional(readOnly = true)
  public Optional<String> findAlignmentIndexJson(UUID scoreId, Integer revision) {
    return find(scoreId, revision).map(ScoreDocumentEntity::getAlignmentIndex);
  }

  @Transactional(readOnly = true)
  public List<ScoreDocumentEntity> findAwaitingReview() {
    return repository.findAwaitingReview();
  }

  @Transactional(readOnly = true)
  public JsonNode parsedDocument(UUID scoreId, Integer revision) {
    return find(scoreId, revision)
        .map(e -> parse(e.getDocument()))
        .orElseThrow(() -> new ScoreDocumentNotFoundException(scoreId, revision));
  }

  private int nextRevision(UUID scoreId) {
    Integer max = repository.findMaxRevision(scoreId);
    return (max == null ? 0 : max) + 1;
  }

  private JsonNode parse(String json) {
    if (json == null || json.isBlank()) {
      throw new InvalidScoreDocumentException("document JSON is empty");
    }
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      throw new InvalidScoreDocumentException("document is not valid JSON: " + e.getMessage());
    }
  }

  private String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
      throw new InvalidScoreDocumentException("document is missing required field: " + field);
    }
    return value.asText();
  }
}
