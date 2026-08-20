package org.pianoml.backend.progress;

/** The checkpoint payload, plus the guest's identity when there is no account. */
public record ProgressRequest(
    Integer stageIndex,
    Integer chunkOrdinal,
    Integer stagesCompleted,
    Integer totalStages,
    Integer tempoPercent,
    Double masteryScore,
    String guestSessionId) {

  public ProgressUpdate toUpdate() {
    return new ProgressUpdate(
        stageIndex, chunkOrdinal, stagesCompleted, totalStages, tempoPercent, masteryScore);
  }
}
