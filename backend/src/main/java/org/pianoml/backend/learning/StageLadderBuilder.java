package org.pianoml.backend.learning;

import org.pianoml.backend.profile.SkillLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the stage ladder for one practice unit.
 *
 * <p>The ladders differ by level in kind, not degree. A complete novice is taken through
 * rhythm before pitch, then one hand at a time with the transport waiting for them
 * indefinitely, then both hands slowly, then speed. Someone who already plays starts at
 * hands-together and spends their time on tempo. Giving a novice the second ladder more
 * slowly does not work: the thing they cannot do is not "play fast".
 */
@Component
public class StageLadderBuilder {

  private static final int TEMPO_GRID_BPM = 5;

  /**
   * Below this a metronome is harder to play to than a moderate tempo — the beats are so
   * far apart that there is no pulse to lock onto, only isolated clicks.
   */
  private static final int MIN_PLAYABLE_BPM = 30;

  /** Beginner tempo rungs, as fractions of the piece's target. */
  private static final double[] BEGINNER_RAMP = {0.40, 0.60, 0.80, 1.00};

  /** Multiplicative ramp for players who can already hold a tempo. */
  private static final double TEMPO_RAMP_FACTOR = 1.10;

  public List<RoadmapStage> build(
      PracticeUnit unit, SkillLevel level, double targetTempo,
      int requestedStartTempo, boolean bothHands, boolean handsSeparateFirst) {

    // Floor the caller's start tempo too. A slow piece scaled down for a beginner can
    // land in the 20s, where the gap between beats stops reading as a pulse at all.
    int startTempo = Math.max(MIN_PLAYABLE_BPM, requestedStartTempo);

    return switch (unit.kind()) {
      case RHYTHM_WARMUP -> rhythmLadder(unit, targetTempo);
      case ASSEMBLY -> assemblyLadder(unit, targetTempo, level);
      case DRILL -> drillLadder(unit, level, targetTempo, startTempo, bothHands, handsSeparateFirst);
    };
  }

  /**
   * Stage 0 — the pulse, with no pitch at all.
   *
   * <p>Any key counts. The learner is training one skill (when to play) instead of two
   * (when, and which). Slow, because the point is to feel the beat land.
   */
  private List<RoadmapStage> rhythmLadder(PracticeUnit unit, double targetTempo) {
    List<RoadmapStage> stages = new ArrayList<>();
    stages.add(new RoadmapStage(
        0, "BOTH", rung(targetTempo, 0.50), StageMode.RHYTHM,
        true, false, false,
        "Tap the rhythm — any key",
        // Pitch accuracy is meaningless here, so the bar is timing alone. Generous:
        // this is the learner's first contact with the piece.
        new MasteryCriterion(0.0, 250, 1, 99),
        estimateMinutes(unit.measureCount(), 1)));
    return stages;
  }

  /**
   * The final run-through, where the tempo actually climbs.
   *
   * <p>Only beginners get this as a separate unit: they have been drilling one bar at a
   * time and have never played the piece end to end, so joining it up is its own task.
   */
  private List<RoadmapStage> assemblyLadder(
      PracticeUnit unit, double targetTempo, SkillLevel level) {

    List<RoadmapStage> stages = new ArrayList<>();
    int ordinal = 0;

    int previous = 0;
    for (double fraction : BEGINNER_RAMP) {
      boolean isFinal = fraction >= 1.0;
      int bpm = rung(targetTempo, fraction);
      if (bpm <= previous && !isFinal) {
        continue;
      }
      previous = bpm;

      stages.add(new RoadmapStage(
          ordinal++, "BOTH", bpm, StageMode.FLOW,
          true, level.needsNoteNames() && !isFinal, false,
          "Whole piece at " + Math.round(fraction * 100) + "%",
          isFinal ? criterion(0.95, 100, 2) : criterion(0.90, 140, 1),
          estimateMinutes(unit.measureCount(), 1)));
    }
    return stages;
  }

  private List<RoadmapStage> drillLadder(
      PracticeUnit unit, SkillLevel level, double targetTempo,
      int startTempo, boolean bothHands, boolean handsSeparateFirst) {

    List<RoadmapStage> stages = new ArrayList<>();
    int ordinal = 0;
    int measures = unit.measureCount();
    boolean labels = level.needsNoteNames();

    // Hands separate, with the transport waiting. ADVANCED skips it: separating the
    // hands is a way to reduce load for someone who is overloaded, and they are not.
    boolean separate = handsSeparateFirst && bothHands && level != SkillLevel.ADVANCED;
    if (separate) {
      for (String hand : new String[] {"RIGHT", "LEFT"}) {
        stages.add(new RoadmapStage(
            ordinal++, hand, startTempo, StageMode.WAIT,
            false, labels, false,
            (hand.equals("RIGHT") ? "Right" : "Left") + " hand — no rush",
            criterion(0.95, 0, level == SkillLevel.BEGINNER_0 ? 1 : 2),
            estimateMinutes(measures, 2)));
      }
    }

    // Hands together, still waiting.
    stages.add(new RoadmapStage(
        ordinal++, "BOTH", startTempo, StageMode.WAIT,
        false, labels, false,
        "Both hands — no rush",
        criterion(0.95, 0, level == SkillLevel.BEGINNER_0 ? 1 : 2),
        estimateMinutes(measures, 2)));

    // Stage 3 for a novice: play one hand in time while the engine covers the other, so
    // they hear the piece whole before they can play it whole.
    if (level == SkillLevel.BEGINNER_0 && bothHands && separate) {
      for (String hand : new String[] {"RIGHT", "LEFT"}) {
        stages.add(new RoadmapStage(
            ordinal++, hand, rung(targetTempo, 0.40), StageMode.FLOW,
            true, labels, true,
            (hand.equals("RIGHT") ? "Right" : "Left") + " hand with accompaniment",
            criterion(0.90, 160, 1),
            estimateMinutes(measures, 1)));
      }
    }

    stages.addAll(tempoRamp(ordinal, level, targetTempo, startTempo, measures, labels));
    return stages;
  }

  /**
   * The tempo ladder.
   *
   * <p>Fixed percentage rungs for both beginner levels — 40/60/80/100 is a plan they can
   * see the end of. Multiplicative rungs for everyone else, which land more finely near
   * the target where the difficulty actually is.
   *
   * <p>Beginners start at 40% of target, and a 10% multiplicative climb from there takes
   * nine rungs to arrive — nine stages per two-bar chunk, each barely distinguishable
   * from the last. That is not a gentle ramp, it is a wall, and it was what this code
   * produced until the generated plans were actually read.
   */
  private List<RoadmapStage> tempoRamp(
      int startOrdinal, SkillLevel level, double targetTempo,
      int startTempo, int measures, boolean labels) {

    List<RoadmapStage> stages = new ArrayList<>();
    int ordinal = startOrdinal;
    int target = roundToGrid(targetTempo);

    if (!level.atLeast(SkillLevel.INTERMEDIATE)) {
      int previous = 0;
      for (double fraction : BEGINNER_RAMP) {
        boolean isFinal = fraction >= 1.0;
        int bpm = rung(targetTempo, fraction);

        // On a slow piece several rungs round to the same tempo once the floor applies.
        // Emitting them anyway would ask the learner to pass the same stage three times
        // over, which reads as the app being stuck rather than as progress.
        if (bpm <= previous && !isFinal) {
          continue;
        }
        previous = bpm;

        stages.add(new RoadmapStage(
            ordinal++, "BOTH", bpm, StageMode.FLOW,
            true, labels && !isFinal, false,
            "Up to " + Math.round(fraction * 100) + "%",
            isFinal ? criterion(0.95, 100, 1) : criterion(0.90, 140, 1),
            estimateMinutes(measures, 1)));
      }
      return stages;
    }

    // First run at tempo, before any climbing.
    stages.add(new RoadmapStage(
        ordinal++, "BOTH", startTempo, StageMode.FLOW,
        true, labels, false, "In time",
        criterion(0.92, 120, 3), estimateMinutes(measures, 3)));

    int current = startTempo;
    while (current < target) {
      current = Math.max(current + TEMPO_GRID_BPM, roundToGrid(current * TEMPO_RAMP_FACTOR));
      int capped = Math.min(current, target);
      boolean isFinal = capped >= target;
      stages.add(new RoadmapStage(
          ordinal++, "BOTH", capped, StageMode.FLOW,
          true, false, false,
          isFinal ? "Full tempo" : capped + " bpm",
          isFinal ? criterion(0.95, 80, 1) : criterion(0.90, 100, 1),
          estimateMinutes(measures, 1)));
      if (isFinal) break;
    }
    return stages;
  }

  private MasteryCriterion criterion(double accuracy, int maxRmsMs, int cleanRuns) {
    return new MasteryCriterion(accuracy, maxRmsMs, cleanRuns, 2);
  }

  private int rung(double targetTempo, double fraction) {
    return Math.max(MIN_PLAYABLE_BPM, roundToGrid(targetTempo * fraction));
  }

  /** Rough: a run takes roughly 4 s per bar, and a stage needs several attempts. */
  private int estimateMinutes(int measures, int cleanRuns) {
    double runSeconds = measures * 4.0;
    double attempts = Math.max(cleanRuns, 1) * 2.5;
    return Math.max(2, (int) Math.ceil(runSeconds * attempts / 60.0));
  }

  private int roundToGrid(double bpm) {
    return (int) (Math.round(bpm / TEMPO_GRID_BPM) * TEMPO_GRID_BPM);
  }
}
