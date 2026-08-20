package org.pianoml.backend.progress;

/**
 * A practice checkpoint.
 *
 * <p>Every field is nullable and means "unchanged", so the surface can report a tempo
 * change without also having to restate the mastery score it did not measure.
 */
public record ProgressUpdate(
    Integer stageIndex,
    Integer chunkOrdinal,
    Integer stagesCompleted,
    Integer totalStages,
    Integer tempoPercent,
    Double masteryScore) {}
