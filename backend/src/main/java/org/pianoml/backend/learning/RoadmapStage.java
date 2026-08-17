package org.pianoml.backend.learning;

/**
 * One practice objective over a chunk.
 *
 * @param handMode RIGHT | LEFT | BOTH
 * @param mode WAIT (transport blocks until the right notes are played) | FLOW (runs on)
 */
public record RoadmapStage(
    int ordinal,
    String handMode,
    int tempoBpm,
    String mode,
    boolean useMetronome,
    MasteryCriterion criterion,
    int estimatedMinutes) {}
