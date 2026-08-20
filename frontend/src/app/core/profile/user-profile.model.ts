/**
 * How much piano the learner already has.
 *
 * This is the one answer that reshapes the practice plan rather than tuning it. The ladder
 * a complete novice needs — tap the rhythm, then find one note at a time with the keyboard
 * labelled — is not a gentler version of the intermediate ladder; it is a different one.
 */
export type SkillLevel = 'BEGINNER_0' | 'BEGINNER_1' | 'INTERMEDIATE' | 'ADVANCED';

/** Whether the learner can read notation. Drives the visual aids, not the ladder. */
export type NotationFluency = 'NONE' | 'SOME' | 'FLUENT';

/** How the learner plays. Remembered so the practice surface opens ready to use. */
export type InputMethod = 'MIDI' | 'QWERTY' | 'TOUCH';

export interface UserProfile {
  skillLevel: SkillLevel;
  notationFluency: NotationFluency;
  preferredInput: InputMethod;
  dailyGoalMinutes: number;
  /**
   * Whether the learner has actually answered the questionnaire.
   *
   * Distinguishes "chose the defaults" from "never asked" — without it the onboarding
   * would either never appear or appear forever.
   */
  onboarded: boolean;
  isGuest: boolean;
  /** Present only for an anonymous visitor. */
  guestSessionId: string | null;
}

export const DEFAULT_PROFILE: UserProfile = {
  skillLevel: 'BEGINNER_1',
  notationFluency: 'SOME',
  // QWERTY, not MIDI: it works for everyone, whereas defaulting to MIDI shows a "no
  // device" state to the majority who have no instrument attached.
  preferredInput: 'QWERTY',
  dailyGoalMinutes: 15,
  onboarded: false,
  isGuest: true,
  guestSessionId: null,
};

/** Human-readable level names, for the header and the library. */
export const SKILL_LEVEL_LABELS: Record<SkillLevel, string> = {
  BEGINNER_0: 'Complete beginner',
  BEGINNER_1: 'Beginner',
  INTERMEDIATE: 'Intermediate',
  ADVANCED: 'Advanced',
};

/** True when the surface should label pitches by default for this learner. */
export function wantsNoteNames(profile: UserProfile): boolean {
  return (
    profile.notationFluency !== 'FLUENT' ||
    profile.skillLevel === 'BEGINNER_0' ||
    profile.skillLevel === 'BEGINNER_1'
  );
}
