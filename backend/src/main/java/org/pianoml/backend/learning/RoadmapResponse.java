package org.pianoml.backend.learning;

import java.util.List;

/**
 * The generated practice roadmap.
 *
 * <p>{@code requiresReview} is surfaced at the top level on purpose: a roadmap built from a
 * score with dropped pages teaches the wrong bars, and a client must be able to say so
 * without walking the chunk list.
 */
public record RoadmapResponse(
    String scoreId,
    int revision,
    String title,
    String composer,
    int measureCount,
    double targetTempoBpm,
    Double globalGrade,
    int totalStages,
    int estimatedMinutes,
    int estimatedWeeks,
    boolean requiresReview,
    String reviewStatus,
    List<RoadmapChunk> chunks) {}
