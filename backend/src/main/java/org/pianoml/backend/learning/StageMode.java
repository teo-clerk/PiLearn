package org.pianoml.backend.learning;

/**
 * How the transport behaves during a stage.
 *
 * <p>Serialised as a plain string in {@link RoadmapStage} so an older client meets an
 * unknown mode rather than a deserialisation failure.
 */
public final class StageMode {

  /**
   * Rhythm only: any key counts as a hit, pitch is ignored.
   *
   * <p>The first thing a complete novice can succeed at. Asking someone to find the right
   * key AND place it in time is two unfamiliar skills at once; this removes one of them.
   */
  public static final String RHYTHM = "RHYTHM";

  /**
   * The transport waits at the current note until the learner plays it.
   *
   * <p>No time pressure at all — the piece simply does not move on without them.
   */
  public static final String WAIT = "WAIT";

  /** The transport runs at tempo regardless; the learner keeps up. */
  public static final String FLOW = "FLOW";

  private StageMode() {}
}
