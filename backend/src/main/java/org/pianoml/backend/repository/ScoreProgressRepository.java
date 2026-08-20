package org.pianoml.backend.repository;

import org.pianoml.backend.entity.ScoreProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Practice progress, always scoped to one identity.
 *
 * <p>Every listing finder takes the guest session as well as the user. Dropping it would
 * show one visitor every other visitor's practice, because all guests share one account.
 */
@Repository
public interface ScoreProgressRepository extends JpaRepository<ScoreProgress, UUID> {

  Optional<ScoreProgress> findByScoreIdAndUserIdAndGuestSessionIdIsNull(
      UUID scoreId, UUID userId);

  Optional<ScoreProgress> findByScoreIdAndGuestSessionId(UUID scoreId, String guestSessionId);

  List<ScoreProgress> findByUserIdAndGuestSessionIdIsNullOrderByLastPracticedAtDesc(UUID userId);

  List<ScoreProgress> findByGuestSessionIdOrderByLastPracticedAtDesc(String guestSessionId);
}
