import { DEFAULT_PROFILE, type UserProfile, wantsNoteNames } from './user-profile.model';

/**
 * When the keys get labelled.
 *
 * Two answers feed this and they come apart in practice: a self-taught player can have
 * real hands and read nothing, and a lapsed clarinettist can read fluently while their
 * hands know no piano. Either reason alone is enough to want labels.
 */
describe('wantsNoteNames', () => {
  const profile = (overrides: Partial<UserProfile>): UserProfile => ({
    ...DEFAULT_PROFILE,
    ...overrides,
  });

  it('labels the keys for someone who has never played', () => {
    expect(
      wantsNoteNames(profile({ skillLevel: 'BEGINNER_0', notationFluency: 'NONE' })),
    ).toBeTrue();
  });

  it('labels the keys for a beginner even if they read fluently', () => {
    // They can read the score and still not know where D♭ is on the instrument.
    expect(
      wantsNoteNames(profile({ skillLevel: 'BEGINNER_0', notationFluency: 'FLUENT' })),
    ).toBeTrue();
  });

  it('labels the keys for a fluent player who cannot read notation', () => {
    expect(
      wantsNoteNames(profile({ skillLevel: 'ADVANCED', notationFluency: 'NONE' })),
    ).toBeTrue();
  });

  it('leaves the keys clean for someone who needs neither aid', () => {
    expect(
      wantsNoteNames(profile({ skillLevel: 'ADVANCED', notationFluency: 'FLUENT' })),
    ).toBeFalse();
  });

  it('defaults to labelling, because guessing wrong costs only a label', () => {
    // Someone who skipped the questionnaire gets the aid. An unwanted label is a minor
    // annoyance; a missing one leaves a beginner unable to find the note at all.
    expect(wantsNoteNames(DEFAULT_PROFILE)).toBeTrue();
  });
});
