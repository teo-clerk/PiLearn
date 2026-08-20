package org.pianoml.backend.profile;

/**
 * Whether the learner can read sheet music.
 *
 * <p>Kept separate from {@link SkillLevel} because the two genuinely come apart: a
 * self-taught player can have real hands and read nothing, and a lapsed clarinettist can
 * read fluently while their hands know no piano at all. Reading drives the visual aids;
 * skill level drives the practice ladder.
 */
public enum NotationFluency {

  /** Needs note names on the keys and on the score. */
  NONE,

  /** Recognises some notes; wants labels as a safety net. */
  SOME,

  /** Reads without help. */
  FLUENT;

  public static final NotationFluency DEFAULT = SOME;

  public static NotationFluency parse(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return DEFAULT;
    }
  }

  /** True when the surface should label pitches by default. */
  public boolean wantsNoteNames() {
    return this != FLUENT;
  }
}
