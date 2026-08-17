package org.pianoml.backend.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A canonical ScoreDocument revision.
 *
 * <p>The document itself is opaque JSONB here on purpose. The worker owns the schema
 * (worker/pilearn_worker/models/score_document.py is authoritative); mirroring its full
 * type graph in Java would create a second definition that drifts. The backend stores and
 * serves the blob, and reads only the few denormalised columns below — the ones it
 * genuinely queries or displays.
 *
 * <p>Immutable per (scoreId, revision): re-analysis inserts a new row so an in-flight
 * learner's plan keeps resolving against the document it was built from.
 */
@Entity
@Table(
    name = "score_document",
    schema = "pianoml",
    uniqueConstraints = @UniqueConstraint(columnNames = {"score_id", "revision"}))
@Getter
@Setter
@NoArgsConstructor
public class ScoreDocumentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "score_id", nullable = false)
  private UUID scoreId;

  @Column(name = "revision", nullable = false)
  private Integer revision;

  @Column(name = "schema_version", nullable = false, length = 16)
  private String schemaVersion;

  @Column(name = "analysis_version", nullable = false, length = 64)
  private String analysisVersion;

  /** The full ScoreDocument. Read whole, never queried by field — hence JSONB. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "document", columnDefinition = "jsonb", nullable = false)
  private String document;

  /** The compact alignment index. Served on the hot path, so kept separate. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "alignment_index", columnDefinition = "jsonb")
  private String alignmentIndex;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "confidence", columnDefinition = "jsonb")
  private String confidence;

  // ── Denormalised for querying and display ────────────────────────────────
  // These duplicate values inside the JSONB. Justified: filtering the library by grade
  // or confidence must not require parsing every document.

  @Column(name = "document_confidence")
  private Double documentConfidence;

  @Column(name = "review_status", length = 32)
  private String reviewStatus;

  @Column(name = "measure_count")
  private Integer measureCount;

  @Column(name = "global_grade")
  private Double globalGrade;

  @Column(name = "source_pages")
  private Integer sourcePages;

  @Column(name = "recognised_pages")
  private Integer recognisedPages;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  /** True when a human must look before this score drives practice. */
  public boolean requiresReview() {
    return "REVIEW_REQUIRED".equals(reviewStatus)
        || (sourcePages != null && recognisedPages != null
            && recognisedPages < sourcePages);
  }
}
