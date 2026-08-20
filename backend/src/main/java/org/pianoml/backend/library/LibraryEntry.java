package org.pianoml.backend.library;

/**
 * One card in the learner's library.
 *
 * <p>Carries enough to render the card and to resume practice without a second request:
 * {@code stageIndex} and {@code tempoPercent} are exactly what the practice surface needs
 * to reopen where the learner left off.
 */
public record LibraryEntry(
    String scoreId,
    String title,
    String composer,
    /** QUEUED | PROCESSING | READY | REVIEW_REQUIRED | FAILED. */
    String status,
    Double difficulty,
    String difficultyLabel,
    Integer measureCount,
    /** 0..1 across the whole roadmap. */
    double progress,
    int stagesCompleted,
    int totalStages,
    int stageIndex,
    int chunkOrdinal,
    int tempoPercent,
    Double masteryScore,
    boolean mastered,
    /** Null until the learner has actually practised it. */
    String lastPracticedAt,
    String uploadedAt,
    /** Human-readable position, e.g. "Chunk 2/6 · 75% BPM". */
    String stageSummary) {}
