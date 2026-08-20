package org.pianoml.backend.learning;

/**
 * One practice objective over a chunk.
 *
 * @param handMode RIGHT | LEFT | BOTH
 * @param mode see {@link StageMode}
 * @param showNoteNames draw pitch names on the keyboard and cursor for this stage
 * @param guideOpposingHand play the other hand back while the learner plays theirs
 */
public record RoadmapStage(
    int ordinal,
    String handMode,
    int tempoBpm,
    String mode,
    boolean useMetronome,
    boolean showNoteNames,
    boolean guideOpposingHand,
    String label,
    MasteryCriterion criterion,
    int estimatedMinutes) {}
