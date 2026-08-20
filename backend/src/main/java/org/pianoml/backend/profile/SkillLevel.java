package org.pianoml.backend.profile;

/**
 * How much piano the learner already has.
 *
 * <p>This is the single input that reshapes a roadmap. The ladder a complete novice needs
 * — tap the rhythm, then find one note at a time with the keyboard labelled — is not a
 * gentler version of the intermediate ladder; it is a different ladder. See
 * {@code RoadmapService}.
 *
 * <p>Ordered from least to most experienced; {@link #atLeast} relies on that order.
 */
public enum SkillLevel {

  /** Has never played. Cannot read notation; still learning where the keys are. */
  BEGINNER_0,

  /** Reads simple notation, plays one hand at a time. */
  BEGINNER_1,

  /** Hands together, simple polyphony, can hold a changing tempo. */
  INTERMEDIATE,

  /** Sight-reads fluently; practises to polish rather than to decode. */
  ADVANCED;

  /** The level assumed for anyone who has not answered the questionnaire. */
  public static final SkillLevel DEFAULT = BEGINNER_1;

  /**
   * Parse a client-supplied level, falling back to {@link #DEFAULT}.
   *
   * <p>Never throws: an unrecognised level is a reason to give someone the default
   * roadmap, not to fail their practice session.
   */
  public static SkillLevel parse(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return DEFAULT;
    }
  }

  public boolean atLeast(SkillLevel other) {
    return ordinal() >= other.ordinal();
  }

  /** True when the learner needs pitch names drawn on the keys and the score. */
  public boolean needsNoteNames() {
    return this == BEGINNER_0 || this == BEGINNER_1;
  }

  /** True when rhythm should be trained on its own before pitch is introduced. */
  public boolean needsRhythmStage() {
    return this == BEGINNER_0;
  }
}
