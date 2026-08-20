package org.pianoml.backend.progress;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.entity.ScoreProgress;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.repository.ScoreProgressRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where each learner has got to with each score.
 *
 * <p>Every lookup takes an {@link OwnerScope} and branches on whether it is a guest. That
 * branch is load-bearing: guests share one account, so a query by user alone would return
 * every visitor's progress to every visitor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScoreProgressService {

  private final ScoreProgressRepository progressRepository;

  @Transactional(readOnly = true)
  public Optional<ScoreProgress> find(OwnerScope scope, UUID scoreId) {
    return scope.isGuest()
        ? progressRepository.findByScoreIdAndGuestSessionId(scoreId, scope.guestSessionId())
        : progressRepository.findByScoreIdAndUserIdAndGuestSessionIdIsNull(
            scoreId, scope.user().getId());
  }

  @Transactional(readOnly = true)
  public List<ScoreProgress> findAll(OwnerScope scope) {
    return scope.isGuest()
        ? progressRepository.findByGuestSessionIdOrderByLastPracticedAtDesc(
            scope.guestSessionId())
        : progressRepository.findByUserIdAndGuestSessionIdIsNullOrderByLastPracticedAtDesc(
            scope.user().getId());
  }

  /** Record where the learner is now. Creates the row on first practice. */
  @Transactional
  public ScoreProgress record(OwnerScope scope, Score score, ProgressUpdate update) {
    ScoreProgress progress = find(scope, score.getId())
        .orElseGet(() -> newProgress(scope, score));
    apply(progress, update);

    try {
      return progressRepository.save(progress);
    } catch (DataIntegrityViolationException e) {
      // Two tabs practising the same score both miss the lookup and both insert.
      ScoreProgress winner = find(scope, score.getId()).orElseThrow(() -> e);
      apply(winner, update);
      return progressRepository.save(winner);
    }
  }

  private void apply(ScoreProgress progress, ProgressUpdate update) {
    if (update.stageIndex() != null) progress.setStageIndex(Math.max(0, update.stageIndex()));
    if (update.chunkOrdinal() != null) {
      progress.setChunkOrdinal(Math.max(0, update.chunkOrdinal()));
    }
    if (update.totalStages() != null) {
      progress.setTotalStages(Math.max(0, update.totalStages()));
    }
    if (update.tempoPercent() != null) {
      progress.setTempoPercent(Math.clamp(update.tempoPercent(), 25, 150));
    }

    // Both of these only ever move forward. A learner who replays an early chunk, or has
    // one bad run, has not un-learned the piece — letting either slide backwards would
    // make the library look like it was punishing them for practising.
    if (update.stagesCompleted() != null) {
      progress.setStagesCompleted(
          Math.max(progress.getStagesCompleted(), Math.max(0, update.stagesCompleted())));
    }
    if (update.masteryScore() != null) {
      double best = progress.getMasteryScore() == null ? 0 : progress.getMasteryScore();
      progress.setMasteryScore(Math.max(best, Math.clamp(update.masteryScore(), 0.0, 1.0)));
    }

    progress.setMastered(
        progress.getTotalStages() > 0
            && progress.getStagesCompleted() >= progress.getTotalStages());

    OffsetDateTime now = OffsetDateTime.now();
    progress.setLastPracticedAt(now);
    progress.setUpdatedAt(now);
  }

  private ScoreProgress newProgress(OwnerScope scope, Score score) {
    ScoreProgress progress = new ScoreProgress();
    progress.setScore(score);
    progress.setUser(scope.user());
    progress.setGuestSessionId(scope.guestSessionId());
    return progress;
  }
}
