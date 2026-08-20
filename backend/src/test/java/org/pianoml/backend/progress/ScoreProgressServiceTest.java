package org.pianoml.backend.progress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.entity.ScoreProgress;
import org.pianoml.backend.entity.User;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.repository.ScoreProgressRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Practice checkpoints.
 *
 * <p>Two rules carry the weight: progress is looked up per identity (guests share an
 * account), and earned progress only ever moves forward. The second is not bookkeeping
 * pedantry — a library that showed someone going backwards for replaying an early bar
 * would be punishing them for practising.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoreProgressServiceTest {

  @Mock private ScoreProgressRepository progressRepository;

  private ScoreProgressService service;
  private Score score;
  private User guest;
  private User account;

  @BeforeEach
  void setUp() {
    service = new ScoreProgressService(progressRepository);

    score = new Score();
    score.setId(UUID.randomUUID());

    guest = new User();
    guest.setId(OwnerScopeResolver.GUEST_USER_ID);
    account = new User();
    account.setId(UUID.randomUUID());

    when(progressRepository.save(any(ScoreProgress.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(progressRepository.findByScoreIdAndGuestSessionId(any(), any()))
        .thenReturn(Optional.empty());
    when(progressRepository.findByScoreIdAndUserIdAndGuestSessionIdIsNull(any(), any()))
        .thenReturn(Optional.empty());
  }

  private OwnerScope guestScope() {
    return new OwnerScope(guest, "guest_mysession12345678", true);
  }

  private ProgressUpdate update(Integer stage, Integer completed, Integer total, Double mastery) {
    return new ProgressUpdate(stage, 0, completed, total, 100, mastery);
  }

  @Test
  @DisplayName("a guest's progress is looked up by session, not by the shared account")
  void guestProgressIsSessionScoped() {
    service.find(guestScope(), score.getId());

    verify(progressRepository)
        .findByScoreIdAndGuestSessionId(score.getId(), "guest_mysession12345678");
    verify(progressRepository, never())
        .findByScoreIdAndUserIdAndGuestSessionIdIsNull(any(), any());
  }

  @Test
  @DisplayName("first practice creates the row with the caller's identity on it")
  void firstPracticeCreatesScopedRow() {
    var saved = service.record(guestScope(), score, update(2, 2, 8, 0.9));

    assertThat(saved.getUser()).isSameAs(guest);
    assertThat(saved.getGuestSessionId()).isEqualTo("guest_mysession12345678");
    assertThat(saved.getScore()).isSameAs(score);
    assertThat(saved.getStageIndex()).isEqualTo(2);
  }

  @Test
  @DisplayName("replaying an earlier stage moves the cursor back but not the achievement")
  void earnedProgressNeverRegresses() {
    ScoreProgress existing = new ScoreProgress();
    existing.setScore(score);
    existing.setUser(account);
    existing.setStagesCompleted(6);
    existing.setTotalStages(8);
    existing.setMasteryScore(0.94);
    when(progressRepository.findByScoreIdAndUserIdAndGuestSessionIdIsNull(any(), any()))
        .thenReturn(Optional.of(existing));

    var scope = new OwnerScope(account, null, false);
    var saved = service.record(scope, score, update(1, 1, 8, 0.42));

    // Where they are: back at stage 1, because that is where they chose to work.
    assertThat(saved.getStageIndex()).isEqualTo(1);
    // What they have earned: unchanged. One weak run does not un-learn six stages.
    assertThat(saved.getStagesCompleted()).isEqualTo(6);
    assertThat(saved.getMasteryScore()).isEqualTo(0.94);
  }

  @Test
  @DisplayName("mastery is derived from finishing the ladder, not claimed by the client")
  void masteryIsDerived() {
    var scope = new OwnerScope(account, null, false);

    assertThat(service.record(scope, score, update(7, 7, 8, 0.99)).getMastered()).isFalse();
    assertThat(service.record(scope, score, update(8, 8, 8, 0.99)).getMastered()).isTrue();
  }

  @Test
  @DisplayName("a roadmap with no stages is never reported as mastered")
  void emptyRoadmapIsNotMastered() {
    // 0 completed of 0 total is arithmetically "all of them" and would otherwise award
    // mastery to every score the moment it was opened.
    var scope = new OwnerScope(account, null, false);

    assertThat(service.record(scope, score, update(0, 0, 0, null)).getMastered()).isFalse();
  }

  @Test
  @DisplayName("out-of-range values are clamped rather than stored")
  void valuesAreClamped() {
    var scope = new OwnerScope(account, null, false);

    var saved = service.record(
        scope, score, new ProgressUpdate(-5, -2, 3, 8, 9000, 4.2));

    assertThat(saved.getStageIndex()).isZero();
    assertThat(saved.getChunkOrdinal()).isZero();
    assertThat(saved.getTempoPercent()).isEqualTo(150);
    assertThat(saved.getMasteryScore()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a null field leaves the stored value alone")
  void nullMeansUnchanged() {
    ScoreProgress existing = new ScoreProgress();
    existing.setScore(score);
    existing.setUser(account);
    existing.setStageIndex(4);
    existing.setTempoPercent(80);
    existing.setTotalStages(8);
    when(progressRepository.findByScoreIdAndUserIdAndGuestSessionIdIsNull(any(), any()))
        .thenReturn(Optional.of(existing));

    var scope = new OwnerScope(account, null, false);
    var saved = service.record(
        scope, score, new ProgressUpdate(null, null, null, null, null, 0.8));

    assertThat(saved.getStageIndex()).isEqualTo(4);
    assertThat(saved.getTempoPercent()).isEqualTo(80);
    assertThat(saved.getMasteryScore()).isEqualTo(0.8);
  }

  @Test
  @DisplayName("practising stamps the time, so the library can sort by most recent")
  void practiceStampsTime() {
    var scope = new OwnerScope(account, null, false);

    assertThat(service.record(scope, score, update(1, 1, 8, null)).getLastPracticedAt())
        .isNotNull();
  }
}
