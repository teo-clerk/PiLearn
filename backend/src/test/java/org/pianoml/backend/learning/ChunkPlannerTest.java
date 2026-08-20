package org.pianoml.backend.learning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pianoml.backend.profile.SkillLevel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How finely the piece is sliced, and for whom.
 *
 * <p>The property that matters: a complete novice never gets a practice unit larger than
 * one bar, and an experienced player never has their phrases cut up. Both failures are
 * silent — the roadmap still renders — and both make it useless.
 */
class ChunkPlannerTest {

  private ChunkPlanner planner;

  @BeforeEach
  void setUp() {
    planner = new ChunkPlanner();
  }

  /** Two four-bar phrases: bars 0-3 and 4-7. */
  private List<ScoreDocumentChunk> twoPhrases() {
    return List.of(
        new ScoreDocumentChunk("c0", 0, 0, 3, 4, 3.0, "PHRASE", "Opening", "CADENCE",
            List.of(), List.of()),
        new ScoreDocumentChunk("c1", 1, 4, 7, 4, 6.0, "PHRASE", "Answer", "CADENCE",
            List.of(), List.of()));
  }

  @Test
  @DisplayName("BEGINNER_0 gets one bar at a time")
  void beginnerZeroGetsSingleBars() {
    var drills = planner.plan(twoPhrases(), 8, SkillLevel.BEGINNER_0).stream()
        .filter(u -> u.kind() == PracticeUnit.Kind.DRILL)
        .toList();

    assertThat(drills).hasSize(8);
    assertThat(drills).allSatisfy(unit -> assertThat(unit.measureCount()).isEqualTo(1));
  }

  @Test
  @DisplayName("BEGINNER_0 is framed by a rhythm warm-up and an assembly run")
  void beginnerZeroIsFramed() {
    var units = planner.plan(twoPhrases(), 8, SkillLevel.BEGINNER_0);

    assertThat(units.get(0).kind()).isEqualTo(PracticeUnit.Kind.RHYTHM_WARMUP);
    assertThat(units.get(0).measureCount()).isEqualTo(8);
    assertThat(units.get(units.size() - 1).kind()).isEqualTo(PracticeUnit.Kind.ASSEMBLY);
    assertThat(units.get(units.size() - 1).measureCount()).isEqualTo(8);
  }

  @Test
  @DisplayName("BEGINNER_1 gets two bars at a time and no whole-piece framing")
  void beginnerOneGetsPairs() {
    var units = planner.plan(twoPhrases(), 8, SkillLevel.BEGINNER_1);

    assertThat(units).allSatisfy(u -> assertThat(u.kind()).isEqualTo(PracticeUnit.Kind.DRILL));
    assertThat(units).hasSize(4);
    assertThat(units).allSatisfy(u -> assertThat(u.measureCount()).isEqualTo(2));
  }

  @Test
  @DisplayName("INTERMEDIATE and ADVANCED keep the worker's musical phrases intact")
  void experiencedPlayersKeepPhrases() {
    for (SkillLevel level : new SkillLevel[] {SkillLevel.INTERMEDIATE, SkillLevel.ADVANCED}) {
      var units = planner.plan(twoPhrases(), 8, level);

      assertThat(units).hasSize(2);
      assertThat(units).allSatisfy(u -> assertThat(u.measureCount()).isEqualTo(4));
      assertThat(units.get(0).startMeasure()).isZero();
      assertThat(units.get(1).startMeasure()).isEqualTo(4);
    }
  }

  @Test
  @DisplayName("a phrase that does not divide evenly still covers every bar exactly once")
  void unevenPhraseIsFullyCovered() {
    // Five bars at two-bar granularity: 2 + 2 + 1, with nothing lost or repeated.
    var chunks = List.of(new ScoreDocumentChunk(
        "c0", 0, 0, 4, 5, 3.0, "PHRASE", "Odd", "CADENCE", List.of(), List.of()));

    var units = planner.plan(chunks, 5, SkillLevel.BEGINNER_1);

    assertThat(units.stream().mapToInt(PracticeUnit::measureCount).sum()).isEqualTo(5);
    assertThat(units.get(0).startMeasure()).isZero();
    assertThat(units.get(units.size() - 1).endMeasure()).isEqualTo(4);
    for (int i = 1; i < units.size(); i++) {
      assertThat(units.get(i).startMeasure()).isEqualTo(units.get(i - 1).endMeasure() + 1);
    }
  }

  @Test
  @DisplayName("units are numbered consecutively from zero, framing included")
  void ordinalsAreConsecutive() {
    var units = planner.plan(twoPhrases(), 8, SkillLevel.BEGINNER_0);

    for (int i = 0; i < units.size(); i++) {
      assertThat(units.get(i).ordinal()).isEqualTo(i);
    }
  }

  @Test
  @DisplayName("labels name bars the learner can find, not internal ordinals")
  void labelsUseBarNumbers() {
    var drills = planner.plan(twoPhrases(), 8, SkillLevel.BEGINNER_0).stream()
        .filter(u -> u.kind() == PracticeUnit.Kind.DRILL)
        .toList();

    // One-based, because bar 1 is what is printed on the page.
    assertThat(drills.get(0).label()).startsWith("Bar 1");
    assertThat(drills.get(4).label()).startsWith("Bar 5");
  }

  @Test
  @DisplayName("no chunks and no measures yields nothing rather than a bogus unit")
  void emptyDocumentYieldsNothing() {
    assertThat(planner.plan(List.of(), 0, SkillLevel.BEGINNER_0)).isEmpty();
  }
}
