package org.pianoml.backend.profile;

/**
 * A partial profile change.
 *
 * <p>Every field is nullable and means "leave this alone", so switching input device does
 * not silently reset the skill level the questionnaire established.
 */
public record ProfileUpdate(
    SkillLevel skillLevel,
    NotationFluency notationFluency,
    InputMethod preferredInput,
    Integer dailyGoalMinutes,
    boolean onboarded) {

  /**
   * Build from the raw request.
   *
   * <p>Unparseable enum values fall back to their defaults rather than 400: an odd value
   * here should give the learner a sensible roadmap, not an error page. An absent field
   * stays null so it is left untouched.
   */
  public static ProfileUpdate from(ProfileRequest request) {
    return new ProfileUpdate(
        request.skillLevel() == null ? null : SkillLevel.parse(request.skillLevel()),
        request.notationFluency() == null
            ? null : NotationFluency.parse(request.notationFluency()),
        request.preferredInput() == null
            ? null : InputMethod.parse(request.preferredInput()),
        request.dailyGoalMinutes(),
        Boolean.TRUE.equals(request.onboarded()));
  }
}
