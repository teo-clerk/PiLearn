package org.pianoml.backend.learning;

/** The test a learner must pass to clear a stage (PRODUCT_SPEC §5.2). */
public record MasteryCriterion(
    double minPitchAccuracy,
    int maxTimingRmsMs,
    int consecutiveCleanRuns,
    int maxErrorsPerMeasure) {

  public MasteryCriterion {
    if (minPitchAccuracy < 0 || minPitchAccuracy > 1) {
      throw new IllegalArgumentException("minPitchAccuracy must be in [0, 1]");
    }
    if (consecutiveCleanRuns < 1) {
      throw new IllegalArgumentException("consecutiveCleanRuns must be at least 1");
    }
  }
}
