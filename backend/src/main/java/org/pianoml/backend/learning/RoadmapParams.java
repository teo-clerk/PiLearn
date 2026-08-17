package org.pianoml.backend.learning;

/** Learner-supplied roadmap generation parameters. */
public record RoadmapParams(double goalTempoPct, boolean handsSeparateFirst) {

  public RoadmapParams {
    if (goalTempoPct <= 0 || goalTempoPct > 2.0) {
      throw new IllegalArgumentException("goalTempoPct must be in (0, 2.0]");
    }
  }

  public static RoadmapParams defaults() {
    return new RoadmapParams(1.0, true);
  }
}
