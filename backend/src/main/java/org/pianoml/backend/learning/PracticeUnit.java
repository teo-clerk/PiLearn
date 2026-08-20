package org.pianoml.backend.learning;

/**
 * A planned practice unit — a measure range plus what it is FOR.
 *
 * <p>Sits between the worker's musical chunks and the roadmap's chunks. The worker
 * segments a score by phrase and cadence; a complete novice needs one bar at a time, and
 * needs a whole-piece pulse exercise before any of it. Both are measure ranges, so they
 * share a type, and {@link Kind} carries the difference.
 */
public record PracticeUnit(
    int ordinal, int startMeasure, int endMeasure, double difficulty, String label, Kind kind) {

  public enum Kind {
    /** The whole piece, tapped for rhythm only. Pitch comes later. */
    RHYTHM_WARMUP,

    /** A short range drilled hands-separate then together. The bulk of the work. */
    DRILL,

    /** The whole piece again, run end to end while the tempo climbs. */
    ASSEMBLY
  }

  public int measureCount() {
    return endMeasure - startMeasure + 1;
  }
}
