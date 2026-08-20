package org.pianoml.backend.learning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pianoml.backend.profile.SkillLevel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the learner is actually asked to do, stage by stage.
 *
 * <p>These assertions encode the pedagogy, so they are written in terms of what a learner
 * experiences — "is never asked for pitch before rhythm", "is never rushed before they can
 * find the notes" — rather than in terms of field values.
 */
class StageLadderBuilderTest {

  private StageLadderBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new StageLadderBuilder();
  }

  private PracticeUnit drill() {
    return new PracticeUnit(1, 0, 0, 4.0, "Bar 1", PracticeUnit.Kind.DRILL);
  }

  private List<RoadmapStage> ladder(SkillLevel level, PracticeUnit unit) {
    return builder.build(unit, level, 120.0, 50, true, true);
  }

  @Nested
  @DisplayName("BEGINNER_0 — never two new skills at once")
  class BeginnerZero {

    @Test
    @DisplayName("the rhythm warm-up ignores pitch entirely")
    void rhythmStageIsPitchIndependent() {
      var unit = new PracticeUnit(0, 0, 7, 1.0, "Feel the pulse",
          PracticeUnit.Kind.RHYTHM_WARMUP);

      var stages = ladder(SkillLevel.BEGINNER_0, unit);

      assertThat(stages).hasSize(1);
      assertThat(stages.get(0).mode()).isEqualTo(StageMode.RHYTHM);
      // Any key counts, so demanding pitch accuracy would fail every attempt.
      assertThat(stages.get(0).criterion().minPitchAccuracy()).isZero();
    }

    @Test
    @DisplayName("the first pitched stages wait indefinitely and are hands separate")
    void firstPitchedStagesWaitAndSeparate() {
      var stages = ladder(SkillLevel.BEGINNER_0, drill());

      assertThat(stages.get(0).mode()).isEqualTo(StageMode.WAIT);
      assertThat(stages.get(0).handMode()).isEqualTo("RIGHT");
      assertThat(stages.get(1).handMode()).isEqualTo("LEFT");
      // A metronome during a stage that waits for you is a contradiction.
      assertThat(stages.get(0).useMetronome()).isFalse();
    }

    @Test
    @DisplayName("note names are shown until the very last stage")
    void noteNamesAreShownWhileLearning() {
      var stages = ladder(SkillLevel.BEGINNER_0, drill());

      assertThat(stages.get(0).showNoteNames()).isTrue();
      // Removing the labels for the final run is the point: they should not need them.
      assertThat(stages.get(stages.size() - 1).showNoteNames()).isFalse();
    }

    @Test
    @DisplayName("the engine covers the opposing hand before both hands are asked for in time")
    void opposingHandIsAccompanied() {
      var stages = ladder(SkillLevel.BEGINNER_0, drill());

      var accompanied = stages.stream().filter(RoadmapStage::guideOpposingHand).toList();
      assertThat(accompanied).isNotEmpty();
      assertThat(accompanied).allSatisfy(
          s -> assertThat(s.handMode()).isIn("RIGHT", "LEFT"));

      int firstAccompanied = stages.indexOf(accompanied.get(0));
      int firstBothInTime = indexOfFirstBothHandsFlow(stages);
      assertThat(firstAccompanied).isLessThan(firstBothInTime);
    }

    @Test
    @DisplayName("the tempo ramp is 40/60/80/100 percent of the target")
    void tempoRampIsExplicit() {
      var stages = ladder(SkillLevel.BEGINNER_0, drill());

      var flowTempos = stages.stream()
          .filter(s -> StageMode.FLOW.equals(s.mode()) && "BOTH".equals(s.handMode()))
          .map(RoadmapStage::tempoBpm)
          .toList();

      // 40/60/80/100 percent of 120 bpm.
      assertThat(flowTempos).containsExactly(50, 70, 95, 120);
    }

    @Test
    @DisplayName("pitch is never demanded before the rhythm stage has been offered")
    void rhythmAlwaysPrecedesPitch() {
      var planner = new ChunkPlanner();
      var chunks = List.of(new ScoreDocumentChunk(
          "c0", 0, 0, 3, 4, 3.0, "PHRASE", "Opening", "CADENCE", List.of(), List.of()));

      var units = planner.plan(chunks, 4, SkillLevel.BEGINNER_0);
      var first = builder.build(units.get(0), SkillLevel.BEGINNER_0, 120.0, 50, true, true);

      assertThat(first.get(0).mode()).isEqualTo(StageMode.RHYTHM);
    }
  }

  @Nested
  @DisplayName("experienced players — start where the difficulty actually is")
  class Experienced {

    @Test
    @DisplayName("INTERMEDIATE gets no rhythm stage and no note names")
    void intermediateSkipsBeginnerAids() {
      var stages = ladder(SkillLevel.INTERMEDIATE, drill());

      assertThat(stages).noneMatch(s -> StageMode.RHYTHM.equals(s.mode()));
      assertThat(stages).noneMatch(RoadmapStage::showNoteNames);
      assertThat(stages).noneMatch(RoadmapStage::guideOpposingHand);
    }

    @Test
    @DisplayName("ADVANCED goes straight to hands together")
    void advancedSkipsHandsSeparate() {
      var stages = ladder(SkillLevel.ADVANCED, drill());

      // Separating the hands lightens the load for someone who is overloaded. They
      // are not, and the stage would only pad the ladder.
      assertThat(stages).allSatisfy(s -> assertThat(s.handMode()).isEqualTo("BOTH"));
    }

    @Test
    @DisplayName("the ramp climbs to exactly the target tempo and stops")
    void rampReachesTargetOnce() {
      var stages = ladder(SkillLevel.INTERMEDIATE, drill());

      var tempos = stages.stream().map(RoadmapStage::tempoBpm).toList();
      assertThat(tempos).allSatisfy(t -> assertThat(t).isLessThanOrEqualTo(120));
      assertThat(tempos.get(tempos.size() - 1)).isEqualTo(120);
      assertThat(tempos.stream().filter(t -> t == 120).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a hands-together-only score never generates hands-separate stages")
    void singleHandScoreSkipsSeparation() {
      var stages = builder.build(
          drill(), SkillLevel.BEGINNER_1, 120.0, 50, false, true);

      assertThat(stages).allSatisfy(s -> assertThat(s.handMode()).isEqualTo("BOTH"));
    }
  }

  @Test
  @DisplayName("a beginner's ladder stays short enough to see the end of")
  void beginnerLaddersAreNotWalls() {
    // A 10% multiplicative climb from 40% of target takes nine rungs to arrive, each
    // barely distinguishable from the last. Beginners get the explicit 40/60/80/100
    // ramp instead — this is the count that regressed once already.
    for (SkillLevel level : new SkillLevel[] {SkillLevel.BEGINNER_0, SkillLevel.BEGINNER_1}) {
      var stages = ladder(level, drill());

      long flowStages = stages.stream()
          .filter(s -> StageMode.FLOW.equals(s.mode()) && "BOTH".equals(s.handMode()))
          .count();

      assertThat(flowStages).as("%s tempo rungs", level).isLessThanOrEqualTo(4);
      assertThat(stages.size()).as("%s total stages", level).isLessThanOrEqualTo(10);
    }
  }

  @Test
  @DisplayName("no stage is ever slower than a pulse a learner can feel")
  void tempoNeverCollapses() {
    // 40% of a slow piece is a beat every two seconds, which stops reading as a pulse.
    var stages = builder.build(drill(), SkillLevel.BEGINNER_0, 40.0, 20, true, true);

    assertThat(stages).allSatisfy(s -> assertThat(s.tempoBpm()).isGreaterThanOrEqualTo(30));
  }

  @Test
  @DisplayName("stages within a unit are numbered consecutively from zero")
  void ordinalsAreConsecutive() {
    for (SkillLevel level : SkillLevel.values()) {
      var stages = ladder(level, drill());
      for (int i = 0; i < stages.size(); i++) {
        assertThat(stages.get(i).ordinal()).as("%s stage %d", level, i).isEqualTo(i);
      }
    }
  }

  @Test
  @DisplayName("every stage carries a label a learner can read")
  void everyStageIsNamed() {
    for (SkillLevel level : SkillLevel.values()) {
      assertThat(ladder(level, drill()))
          .allSatisfy(s -> assertThat(s.label()).isNotBlank());
    }
  }

  private int indexOfFirstBothHandsFlow(List<RoadmapStage> stages) {
    for (int i = 0; i < stages.size(); i++) {
      if (StageMode.FLOW.equals(stages.get(i).mode())
          && "BOTH".equals(stages.get(i).handMode())) {
        return i;
      }
    }
    return stages.size();
  }
}
