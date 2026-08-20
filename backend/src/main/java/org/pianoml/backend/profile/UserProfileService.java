package org.pianoml.backend.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.UserProfile;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.repository.UserProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Reads and writes the learner's profile.
 *
 * <p>Every lookup goes through {@link #find}, which branches on whether the caller is a
 * guest. That branch is the whole point of the class: guests share one account, so looking
 * a profile up by user alone would give the first guest's answers to everyone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileService {

  private static final int MIN_DAILY_GOAL_MINUTES = 5;
  private static final int MAX_DAILY_GOAL_MINUTES = 240;

  private final UserProfileRepository profileRepository;

  @Transactional(readOnly = true)
  public Optional<UserProfile> find(OwnerScope scope) {
    return scope.isGuest()
        ? profileRepository.findByGuestSessionId(scope.guestSessionId())
        : profileRepository.findByUserIdAndGuestSessionIdIsNull(scope.user().getId());
  }

  /**
   * The learner's profile, or an unsaved default one.
   *
   * <p>Deliberately does not persist: a visitor who has merely opened the site should not
   * leave a row behind, and {@code onboarded == false} is what tells the frontend to ask
   * the questionnaire.
   */
  @Transactional(readOnly = true)
  public UserProfile findOrDefault(OwnerScope scope) {
    return find(scope).orElseGet(() -> newProfile(scope));
  }

  /** Save the questionnaire answers, creating the profile on first use. */
  @Transactional
  public UserProfile save(OwnerScope scope, ProfileUpdate update) {
    UserProfile profile = find(scope).orElseGet(() -> newProfile(scope));
    apply(profile, update);

    try {
      return profileRepository.save(profile);
    } catch (DataIntegrityViolationException e) {
      // Two tabs answering the questionnaire at once both miss the lookup and both
      // insert. The loser re-reads and applies its answers rather than failing — losing
      // a race is not the learner's problem.
      UserProfile winner = find(scope).orElseThrow(() -> e);
      apply(winner, update);
      return profileRepository.save(winner);
    }
  }

  private void apply(UserProfile profile, ProfileUpdate update) {
    if (update.skillLevel() != null) profile.setSkillLevel(update.skillLevel());
    if (update.notationFluency() != null) profile.setNotationFluency(update.notationFluency());
    if (update.preferredInput() != null) profile.setPreferredInput(update.preferredInput());
    if (update.dailyGoalMinutes() != null) {
      profile.setDailyGoalMinutes(clampGoal(update.dailyGoalMinutes()));
    }
    // Only ever set forward. A later partial update (say, switching input device) must
    // not push someone back through onboarding they already completed.
    if (update.onboarded()) profile.setOnboarded(true);
    profile.setUpdatedAt(OffsetDateTime.now());
  }

  private UserProfile newProfile(OwnerScope scope) {
    UserProfile profile = new UserProfile();
    profile.setUser(scope.user());
    profile.setGuestSessionId(scope.guestSessionId());
    return profile;
  }

  /** A goal of 0 or 10 hours helps nobody; clamp rather than reject. */
  private int clampGoal(int minutes) {
    return Math.clamp(minutes, MIN_DAILY_GOAL_MINUTES, MAX_DAILY_GOAL_MINUTES);
  }
}
