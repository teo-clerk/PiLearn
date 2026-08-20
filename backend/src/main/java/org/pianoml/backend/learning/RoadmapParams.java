package org.pianoml.backend.learning;

import org.pianoml.backend.profile.SkillLevel;

/**
 * Learner-supplied roadmap generation parameters.
 *
 * <p>{@code skillLevel} is the one that reshapes the plan rather than tuning it: a
 * complete novice gets a different ladder, not a slower one. See {@link RoadmapService}.
 */
public record RoadmapParams(
    double goalTempoPct, boolean handsSeparateFirst, SkillLevel skillLevel) {

  public RoadmapParams {
    if (goalTempoPct <= 0 || goalTempoPct > 2.0) {
      throw new IllegalArgumentException("goalTempoPct must be in (0, 2.0]");
    }
    if (skillLevel == null) {
      skillLevel = SkillLevel.DEFAULT;
    }
  }

  public static RoadmapParams defaults() {
    return new RoadmapParams(1.0, true, SkillLevel.DEFAULT);
  }

  public static RoadmapParams forLevel(SkillLevel level) {
    return new RoadmapParams(1.0, true, level);
  }
}
