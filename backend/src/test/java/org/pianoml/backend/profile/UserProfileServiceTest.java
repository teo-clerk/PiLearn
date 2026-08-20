package org.pianoml.backend.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.entity.User;
import org.pianoml.backend.entity.UserProfile;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.repository.UserProfileRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The learner's answers to the questionnaire.
 *
 * <p>Two things are easy to get wrong here and invisible when you do: looking a guest's
 * profile up by the shared account (everyone gets the first guest's answers), and letting
 * a later partial update clear {@code onboarded} (the questionnaire reappears forever).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProfileServiceTest {

  @Mock private UserProfileRepository profileRepository;

  private UserProfileService service;
  private User guest;
  private User account;

  @BeforeEach
  void setUp() {
    service = new UserProfileService(profileRepository);

    guest = new User();
    guest.setId(OwnerScopeResolver.GUEST_USER_ID);
    account = new User();
    account.setId(UUID.randomUUID());

    when(profileRepository.save(any(UserProfile.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(profileRepository.findByGuestSessionId(any())).thenReturn(Optional.empty());
    when(profileRepository.findByUserIdAndGuestSessionIdIsNull(any()))
        .thenReturn(Optional.empty());
  }

  private OwnerScope guestScope() {
    return new OwnerScope(guest, "guest_mysession12345678", true);
  }

  @Test
  @DisplayName("a guest's profile is looked up by session, not by the shared account")
  void guestProfileIsSessionScoped() {
    service.find(guestScope());

    verify(profileRepository).findByGuestSessionId("guest_mysession12345678");
    verify(profileRepository, never()).findByUserIdAndGuestSessionIdIsNull(any());
  }

  @Test
  @DisplayName("someone we have never met gets defaults without a row being written")
  void unknownVisitorGetsUnsavedDefaults() {
    // Merely opening the site should not leave a row behind, and onboarded=false is
    // what tells the frontend to ask the questions.
    var profile = service.findOrDefault(guestScope());

    assertThat(profile.getSkillLevel()).isEqualTo(SkillLevel.DEFAULT);
    assertThat(profile.getOnboarded()).isFalse();
    verify(profileRepository, never()).save(any());
  }

  @Test
  @DisplayName("answering the questionnaire saves the answers against the caller")
  void answersAreSaved() {
    var saved = service.save(guestScope(), new ProfileUpdate(
        SkillLevel.BEGINNER_0, NotationFluency.NONE, InputMethod.TOUCH, 20, true));

    assertThat(saved.getSkillLevel()).isEqualTo(SkillLevel.BEGINNER_0);
    assertThat(saved.getNotationFluency()).isEqualTo(NotationFluency.NONE);
    assertThat(saved.getPreferredInput()).isEqualTo(InputMethod.TOUCH);
    assertThat(saved.getDailyGoalMinutes()).isEqualTo(20);
    assertThat(saved.getOnboarded()).isTrue();
    assertThat(saved.getGuestSessionId()).isEqualTo("guest_mysession12345678");
  }

  @Test
  @DisplayName("changing input device later does not send them back through onboarding")
  void onboardingIsNeverUndone() {
    UserProfile existing = new UserProfile();
    existing.setUser(account);
    existing.setSkillLevel(SkillLevel.INTERMEDIATE);
    existing.setOnboarded(true);
    when(profileRepository.findByUserIdAndGuestSessionIdIsNull(any()))
        .thenReturn(Optional.of(existing));

    var scope = new OwnerScope(account, null, false);
    var saved = service.save(scope, new ProfileUpdate(
        null, null, InputMethod.MIDI, null, false));

    assertThat(saved.getOnboarded()).isTrue();
    assertThat(saved.getPreferredInput()).isEqualTo(InputMethod.MIDI);
    // Untouched fields stay untouched.
    assertThat(saved.getSkillLevel()).isEqualTo(SkillLevel.INTERMEDIATE);
  }

  @Test
  @DisplayName("an absurd daily goal is clamped rather than rejected")
  void dailyGoalIsClamped() {
    var scope = new OwnerScope(account, null, false);

    assertThat(service.save(scope, new ProfileUpdate(null, null, null, 0, true))
        .getDailyGoalMinutes()).isEqualTo(5);
    assertThat(service.save(scope, new ProfileUpdate(null, null, null, 10_000, true))
        .getDailyGoalMinutes()).isEqualTo(240);
  }

  @Test
  @DisplayName("an unrecognised level from the client degrades to the default")
  void unknownLevelDegrades() {
    // A stale or hand-edited client should get a sensible roadmap, not an error page.
    var update = ProfileUpdate.from(new ProfileRequest(
        "WIZARD", "TELEPATHY", "OCARINA", 30, true, null));

    assertThat(update.skillLevel()).isEqualTo(SkillLevel.DEFAULT);
    assertThat(update.notationFluency()).isEqualTo(NotationFluency.DEFAULT);
    assertThat(update.preferredInput()).isEqualTo(InputMethod.DEFAULT);
  }

  @Test
  @DisplayName("an omitted field stays null so it is left alone")
  void omittedFieldsStayNull() {
    var update = ProfileUpdate.from(
        new ProfileRequest(null, null, "MIDI", null, null, null));

    assertThat(update.skillLevel()).isNull();
    assertThat(update.notationFluency()).isNull();
    assertThat(update.preferredInput()).isEqualTo(InputMethod.MIDI);
    assertThat(update.onboarded()).isFalse();
  }
}
