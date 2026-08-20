package org.pianoml.backend.library;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.entity.Author;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.entity.ScoreProgress;
import org.pianoml.backend.entity.User;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.ingestion.ScoreIngestionService;
import org.pianoml.backend.progress.ScoreProgressService;
import org.pianoml.backend.repository.ScoreRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The learner's own scores.
 *
 * <p>The first test is the one that matters: every guest shares a single seeded account,
 * so a library that queried by owner alone would show each anonymous visitor every other
 * visitor's uploads. That failure is silent — the page renders perfectly — which is why it
 * is pinned here rather than left to review.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibraryServiceTest {

  @Mock private ScoreRepository scoreRepository;
  @Mock private ScoreProgressService progressService;

  private LibraryService service;
  private User guest;
  private User account;

  @BeforeEach
  void setUp() {
    service = new LibraryService(scoreRepository, progressService);

    guest = new User();
    guest.setId(OwnerScopeResolver.GUEST_USER_ID);
    account = new User();
    account.setId(UUID.randomUUID());

    when(progressService.findAll(any())).thenReturn(List.of());
    when(scoreRepository.findByGuestSessionIdAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of());
    when(scoreRepository
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of());
  }

  private Score score(String title, String status) {
    Score score = new Score();
    score.setId(UUID.randomUUID());
    score.setTitle(title);
    score.setProcessingStatus(status);
    score.setUpdatedAt(OffsetDateTime.now());

    Author author = new Author();
    author.setName("Satie");
    score.setAuthor(author);
    return score;
  }

  @Test
  @DisplayName("a guest library is fetched by session, never by the shared guest account")
  void guestLibraryIsSessionScoped() {
    var scope = new OwnerScope(guest, "guest_mysession12345678", true);

    service.forOwner(scope);

    verify(scoreRepository)
        .findByGuestSessionIdAndDeletedFalseOrderByUpdatedAtDesc("guest_mysession12345678");
    // Querying by owner id here would return every guest's uploads at once.
    verify(scoreRepository, never())
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any());
  }

  @Test
  @DisplayName("a signed-in library is fetched by account, ignoring guest rows")
  void accountLibraryIsOwnerScoped() {
    var scope = new OwnerScope(account, null, false);

    service.forOwner(scope);

    verify(scoreRepository)
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(
            eq(account.getId()));
    verify(scoreRepository, never())
        .findByGuestSessionIdAndDeletedFalseOrderByUpdatedAtDesc(any());
  }

  @Test
  @DisplayName("a score with no practice yet still appears, marked not started")
  void unpractisedScoreStillListed() {
    // The join that would drop these is the tempting one to write, and it hides exactly
    // the scores a learner most wants to see: the ones they just uploaded.
    var scope = new OwnerScope(account, null, false);
    when(scoreRepository
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of(score("Gymnopédie", ScoreIngestionService.STATUS_COMPLETED)));

    var entries = service.forOwner(scope);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).progress()).isZero();
    assertThat(entries.get(0).stageSummary()).isEqualTo("Not started");
    assertThat(entries.get(0).lastPracticedAt()).isNull();
  }

  @Test
  @DisplayName("progress is matched to its own score, not to the first one listed")
  void progressIsMatchedPerScore() {
    var scope = new OwnerScope(account, null, false);
    Score first = score("First", ScoreIngestionService.STATUS_COMPLETED);
    Score second = score("Second", ScoreIngestionService.STATUS_COMPLETED);
    when(scoreRepository
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of(first, second));

    ScoreProgress progress = new ScoreProgress();
    progress.setScore(second);
    progress.setStageIndex(3);
    progress.setChunkOrdinal(1);
    progress.setStagesCompleted(3);
    progress.setTotalStages(6);
    progress.setTempoPercent(75);
    when(progressService.findAll(any())).thenReturn(List.of(progress));

    var entries = service.forOwner(scope);

    assertThat(entries.get(0).stageSummary()).isEqualTo("Not started");
    assertThat(entries.get(1).progress()).isEqualTo(0.5);
    assertThat(entries.get(1).stageSummary()).isEqualTo("Stage 4/6 · chunk 2 · 75% BPM");
  }

  @Test
  @DisplayName("ingestion status is reported in the same words the polling screen uses")
  void statusVocabularyMatchesPolling() {
    var scope = new OwnerScope(account, null, false);
    when(scoreRepository
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of(
            score("a", ScoreIngestionService.STATUS_COMPLETED),
            score("b", ScoreIngestionService.STATUS_RUNNING),
            score("c", ScoreIngestionService.STATUS_FAILED),
            score("d", ScoreIngestionService.STATUS_REVIEW_REQUIRED),
            score("e", ScoreIngestionService.STATUS_QUEUED)));

    var statuses = service.forOwner(scope).stream().map(LibraryEntry::status).toList();

    // A library card and a polling screen disagreeing about readiness is worse than
    // either being wrong alone.
    assertThat(statuses)
        .containsExactly("READY", "PROCESSING", "FAILED", "REVIEW_REQUIRED", "QUEUED");
  }

  @Test
  @DisplayName("a finished roadmap reads as mastered")
  void masteredScoreIsLabelled() {
    var scope = new OwnerScope(account, null, false);
    Score only = score("Done", ScoreIngestionService.STATUS_COMPLETED);
    when(scoreRepository
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of(only));

    ScoreProgress progress = new ScoreProgress();
    progress.setScore(only);
    progress.setStagesCompleted(8);
    progress.setTotalStages(8);
    progress.setTempoPercent(100);
    progress.setMastered(true);
    when(progressService.findAll(any())).thenReturn(List.of(progress));

    var entry = service.forOwner(scope).get(0);

    assertThat(entry.mastered()).isTrue();
    assertThat(entry.progress()).isEqualTo(1.0);
    assertThat(entry.stageSummary()).startsWith("Mastered");
  }

  @Test
  @DisplayName("an unanalysed score has no difficulty badge rather than a made-up one")
  void unknownDifficultyIsNull() {
    var scope = new OwnerScope(account, null, false);
    when(scoreRepository
        .findByOwnerIdAndGuestSessionIdIsNullAndDeletedFalseOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of(score("New", ScoreIngestionService.STATUS_QUEUED)));

    var entry = service.forOwner(scope).get(0);

    assertThat(entry.difficulty()).isNull();
    assertThat(entry.difficultyLabel()).isNull();
  }
}
