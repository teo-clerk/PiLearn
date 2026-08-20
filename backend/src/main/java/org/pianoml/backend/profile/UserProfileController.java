package org.pianoml.backend.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.UserProfile;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The learner's skill profile.
 *
 * <p>Open to anonymous visitors by design: the questionnaire is the first thing a new
 * visitor sees, and gating it behind a sign-up would put the account wall before the
 * product again.
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Slf4j
public class UserProfileController {

  private final OwnerScopeResolver ownerResolver;
  private final UserProfileService profileService;

  /**
   * The current profile, or the defaults when there is none yet.
   *
   * <p>A visitor with no session id gets an un-onboarded default rather than a 404 — the
   * frontend asks "should I show the questionnaire?", and "who are you?" is not a useful
   * answer to that question.
   */
  @GetMapping
  public ResponseEntity<ProfileResponse> get(
      @RequestParam(required = false) String guestSessionId) {

    OwnerScope scope = ownerResolver.resolveExisting(guestSessionId);
    if (scope == null) {
      return ResponseEntity.ok(anonymousDefault());
    }
    return ResponseEntity.ok(
        ProfileResponse.from(profileService.findOrDefault(scope), scope.isGuest()));
  }

  /**
   * Save the questionnaire answers.
   *
   * <p>Uses {@code resolve}, not {@code resolveExisting}: this is the write that
   * establishes a guest identity, so a visitor arriving without a session id is given one
   * and told what it is.
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProfileResponse> save(@RequestBody ProfileRequest request) {
    OwnerScope scope = ownerResolver.resolve(request.guestSessionId());
    UserProfile saved = profileService.save(scope, ProfileUpdate.from(request));

    log.info(
        "profile saved for {} — level {}, input {}",
        scope.isGuest() ? "guest " + scope.guestSessionId() : "user " + scope.user().getId(),
        saved.getSkillLevel(), saved.getPreferredInput());

    return ResponseEntity.ok(ProfileResponse.from(saved, scope.isGuest()));
  }

  /** Defaults for someone we have never seen, with no identity minted. */
  private ProfileResponse anonymousDefault() {
    return new ProfileResponse(
        SkillLevel.DEFAULT.name(),
        NotationFluency.DEFAULT.name(),
        InputMethod.DEFAULT.name(),
        15,
        false,
        true,
        null);
  }
}
