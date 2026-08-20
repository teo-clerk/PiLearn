package org.pianoml.backend.profile;

/** How the learner plays. Remembered so the practice surface opens ready to use. */
public enum InputMethod {

  /** A real instrument over WebMIDI. */
  MIDI,

  /** The computer keyboard, mapped to two octaves. */
  QWERTY,

  /** The on-screen keyboard, by mouse or touch. */
  TOUCH;

  /**
   * The safe default.
   *
   * <p>QWERTY rather than MIDI: it works for everyone, whereas defaulting to MIDI shows a
   * "no device" state to the majority who have no instrument attached.
   */
  public static final InputMethod DEFAULT = QWERTY;

  public static InputMethod parse(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return DEFAULT;
    }
  }
}
