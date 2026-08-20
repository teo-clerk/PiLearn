package org.pianoml.backend.repository;

import org.pianoml.backend.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Profiles, always looked up by identity rather than by user alone.
 *
 * <p>The two finders are not a convenience pair — they are the account case and the guest
 * case, and using the wrong one gives every anonymous visitor the same profile. Callers go
 * through {@code UserProfileService}, which picks based on the {@code OwnerScope}.
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

  Optional<UserProfile> findByUserIdAndGuestSessionIdIsNull(UUID userId);

  Optional<UserProfile> findByGuestSessionId(String guestSessionId);
}
