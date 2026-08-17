package org.pianoml.backend.learning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A pedagogical practice unit, as emitted by the worker.
 *
 * <p>Mirrors {@code Chunk} in {@code worker/pilearn_worker/models/score_document.py}.
 * Field names use snake_case because that is the wire format; renaming them here would
 * need a mapping layer that is a third place to drift.
 *
 * <p>Distinct from a segment: a segment is a musical phrase, a chunk is what a learner
 * practises in one sitting. They are derived from the same analysis but are not 1:1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreDocumentChunk(
    String id,
    int ordinal,
    @JsonProperty("start_measure") int startMeasure,
    @JsonProperty("end_measure") int endMeasure,
    @JsonProperty("measure_count") int measureCount,
    double difficulty,
    String kind,
    String label,
    @JsonProperty("boundary_reason") String boundaryReason,
    @JsonProperty("segment_ids") List<String> segmentIds,
    List<String> patterns) {

  /** A single hard bar isolated for focused work; never merged with a neighbour. */
  public boolean isMicro() {
    return "MICRO".equals(kind);
  }
}
