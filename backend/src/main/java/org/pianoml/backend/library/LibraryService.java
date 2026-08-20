package org.pianoml.backend.library;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.entity.ScoreProgress;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.ingestion.ScoreIngestionService;
import org.pianoml.backend.progress.ScoreProgressService;
import org.pianoml.backend.repository.ScoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The learner's own scores, with how far they have got.
 *
 * <p>Assembled from two tables because they answer different questions and have different
 * lifetimes: a score exists from the moment it is uploaded, whereas progress appears only
 * once someone practises. Joining in the query would silently drop every score the learner
 * has uploaded but not yet opened — which is precisely the list they most want to see.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LibraryService {

  private final ScoreRepository scoreRepository;
  private final ScoreProgressService progressService;

  @Transactional(readOnly = true)
  public List<LibraryEntry> forOwner(OwnerScope scope) {
    List<Score> scores = scope.isGuest()
        ? scoreRepository.findByGuestSessionIdAndDeletedFalseOrderByUpdatedAtDesc(
            scope.guestSessionId())
        : scoreRepository.findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(
            scope.user().getId());

    Map<UUID, ScoreProgress> progressByScore = new HashMap<>();
    for (ScoreProgress progress : progressService.findAll(scope)) {
      if (progress.getScore() != null) {
        progressByScore.put(progress.getScore().getId(), progress);
      }
    }

    List<LibraryEntry> entries = new ArrayList<>(scores.size());
    for (Score score : scores) {
      entries.add(toEntry(score, progressByScore.get(score.getId())));
    }
    return entries;
  }

  private LibraryEntry toEntry(Score score, ScoreProgress progress) {
    int totalStages = progress == null ? 0 : progress.getTotalStages();
    int completed = progress == null ? 0 : progress.getStagesCompleted();
    int tempoPercent = progress == null ? 100 : progress.getTempoPercent();
    int stageIndex = progress == null ? 0 : progress.getStageIndex();
    int chunkOrdinal = progress == null ? 0 : progress.getChunkOrdinal();

    double fraction = totalStages == 0 ? 0.0 : Math.min(1.0, (double) completed / totalStages);

    return new LibraryEntry(
        score.getId().toString(),
        score.getTitle(),
        score.getAuthor() == null ? "Unknown" : score.getAuthor().getName(),
        clientStatus(score),
        score.getGrade() == null ? null : score.getGrade().doubleValue(),
        difficultyLabel(score.getGrade()),
        score.getMeasuresCount(),
        Math.round(fraction * 1000.0) / 1000.0,
        completed,
        totalStages,
        stageIndex,
        chunkOrdinal,
        tempoPercent,
        progress == null ? null : progress.getMasteryScore(),
        progress != null && Boolean.TRUE.equals(progress.getMastered()),
        format(progress == null ? null : progress.getLastPracticedAt()),
        format(score.getUploadedAt() != null ? score.getUploadedAt() : score.getUpdatedAt()),
        summarise(progress, chunkOrdinal, tempoPercent));
  }

  /**
   * Translate the internal ingestion status to the vocabulary the client already speaks.
   *
   * <p>Same five values as {@code ScoreStatusResponse}. A library card and a polling
   * screen disagreeing about whether a score is ready would be worse than either being
   * wrong on its own.
   */
  private String clientStatus(Score score) {
    String stored = score.getProcessingStatus() == null
        ? ScoreIngestionService.STATUS_NONE
        : score.getProcessingStatus();

    return switch (stored) {
      case ScoreIngestionService.STATUS_COMPLETED -> "READY";
      case ScoreIngestionService.STATUS_REVIEW_REQUIRED -> "REVIEW_REQUIRED";
      case ScoreIngestionService.STATUS_FAILED -> "FAILED";
      case ScoreIngestionService.STATUS_RUNNING -> "PROCESSING";
      default -> "QUEUED";
    };
  }

  /** Null until the piece has been analysed — a made-up grade is worse than none. */
  private String difficultyLabel(Float grade) {
    if (grade == null) {
      return null;
    }
    float value = grade;
    if (value < 2.5f) return "Beginner";
    if (value < 5.0f) return "Easy";
    if (value < 7.0f) return "Intermediate";
    if (value < 8.5f) return "Advanced";
    return "Virtuoso";
  }

  /** "Chunk 2/6 · 75% BPM", or an invitation when they have not started. */
  private String summarise(ScoreProgress progress, int chunkOrdinal, int tempoPercent) {
    if (progress == null || progress.getTotalStages() == 0) {
      return "Not started";
    }
    if (Boolean.TRUE.equals(progress.getMastered())) {
      return "Mastered · " + tempoPercent + "% BPM";
    }
    return "Stage " + (progress.getStageIndex() + 1) + "/" + progress.getTotalStages()
        + " · chunk " + (chunkOrdinal + 1)
        + " · " + tempoPercent + "% BPM";
  }

  private String format(OffsetDateTime moment) {
    return moment == null ? null : moment.toString();
  }
}
