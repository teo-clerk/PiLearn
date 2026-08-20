package org.pianoml.backend.profile;

import org.pianoml.backend.entity.UserProfile;

/**
 * The learner's profile as the client sees it.
 *
 * <p>{@code onboarded} is what the frontend gates the questionnaire on, and
 * {@code guestSessionId} is how an anonymous visitor keeps one identity — the client
 * stores whatever comes back and sends it again.
 */
public record ProfileResponse(
    String skillLevel,
    String notationFluency,
    String preferredInput,
    int dailyGoalMinutes,
    boolean onboarded,
    boolean isGuest,
    String guestSessionId) {

  public static ProfileResponse from(UserProfile profile, boolean isGuest) {
    return new ProfileResponse(
        profile.getSkillLevel().name(),
        profile.getNotationFluency().name(),
        profile.getPreferredInput().name(),
        profile.getDailyGoalMinutes(),
        Boolean.TRUE.equals(profile.getOnboarded()),
        isGuest,
        profile.getGuestSessionId());
  }
}
