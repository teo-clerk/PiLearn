package org.pianoml.backend.learning;

import org.pianoml.backend.profile.SkillLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what the learner practises, and in what size pieces.
 *
 * <p>The worker segments a score musically — by phrase and cadence — which is the right
 * unit for someone who can already play. It is the wrong unit for a complete novice, for
 * whom a four-bar phrase is not one thing to learn but four. So a beginner's plan
 * subdivides those phrases, and brackets them with two whole-piece units: a rhythm warm-up
 * before any pitch, and an assembly run at the end.
 *
 * <p>Subdividing here rather than in the worker is deliberate: chunk boundaries need
 * musical analysis and belong with music21, but how finely to slice them is a pedagogical
 * choice that depends on who is asking, which the worker does not know.
 */
@Component
public class ChunkPlanner {

  /** How many measures a single drill covers, by level. */
  private static final int BEGINNER_0_MEASURES = 1;
  private static final int BEGINNER_1_MEASURES = 2;

  public List<PracticeUnit> plan(
      List<ScoreDocumentChunk> documentChunks, int measureCount, SkillLevel level) {

    List<PracticeUnit> units = new ArrayList<>();
    int ordinal = 0;

    boolean wholePieceFraming = level == SkillLevel.BEGINNER_0 && measureCount > 0;

    if (wholePieceFraming) {
      units.add(new PracticeUnit(
          ordinal++, 0, measureCount - 1, 1.0,
          "Feel the pulse", PracticeUnit.Kind.RHYTHM_WARMUP));
    }

    for (ScoreDocumentChunk chunk : documentChunks) {
      for (int[] range : subdivide(chunk, level)) {
        units.add(new PracticeUnit(
            ordinal++, range[0], range[1], chunk.difficulty(),
            label(chunk, range, documentChunks.size()), PracticeUnit.Kind.DRILL));
      }
    }

    if (wholePieceFraming) {
      units.add(new PracticeUnit(
          ordinal, 0, measureCount - 1, 1.0,
          "Put it together", PracticeUnit.Kind.ASSEMBLY));
    }

    return units;
  }

  /**
   * Split one musical chunk into drill-sized ranges.
   *
   * <p>An intermediate player keeps the phrase intact — that is the unit they think in.
   * A beginner gets it in bars.
   */
  private List<int[]> subdivide(ScoreDocumentChunk chunk, SkillLevel level) {
    int span = maxMeasures(level);
    List<int[]> ranges = new ArrayList<>();

    if (span <= 0 || chunk.endMeasure() < chunk.startMeasure()) {
      ranges.add(new int[] {chunk.startMeasure(), chunk.endMeasure()});
      return ranges;
    }

    for (int start = chunk.startMeasure(); start <= chunk.endMeasure(); start += span) {
      ranges.add(new int[] {start, Math.min(start + span - 1, chunk.endMeasure())});
    }
    return ranges;
  }

  /** 0 means "do not subdivide" — keep the worker's musical boundary. */
  private int maxMeasures(SkillLevel level) {
    return switch (level) {
      case BEGINNER_0 -> BEGINNER_0_MEASURES;
      case BEGINNER_1 -> BEGINNER_1_MEASURES;
      case INTERMEDIATE, ADVANCED -> 0;
    };
  }

  /**
   * Name the unit for the learner.
   *
   * <p>Bar numbers, not chunk ordinals: "Bars 5-6" is something they can find on the page,
   * whereas "Chunk 3 part 2" is an implementation detail leaking into the UI. The phrase
   * label is kept alongside when the piece has more than one phrase, so a subdivided
   * range still says which phrase it came from.
   */
  private String label(ScoreDocumentChunk chunk, int[] range, int totalChunks) {
    String bars = range[0] == range[1]
        ? "Bar " + (range[0] + 1)
        : "Bars " + (range[0] + 1) + "-" + (range[1] + 1);

    boolean subdivided = range[0] != chunk.startMeasure() || range[1] != chunk.endMeasure();
    if (!subdivided || totalChunks <= 1 || chunk.label() == null || chunk.label().isBlank()) {
      return bars;
    }
    return bars + " · " + chunk.label();
  }
}
