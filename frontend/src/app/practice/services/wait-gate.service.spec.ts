import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AlignmentCursorService } from './alignment-cursor.service';
import { WaitGateService } from './wait-gate.service';

/**
 * The gate that makes "Wait-for-Me" work.
 *
 * The behaviour under test is a promise to the learner: the cursor does not move until
 * they have played the notes, however long that takes, and a wrong note along the way
 * costs them nothing. Break either and the mode stops being what it says it is.
 */
describe('WaitGateService', () => {
  let gate: WaitGateService;

  /** Stand-in cursor: `expectedPitches` is a signal on the real service. */
  const pitches = signal<readonly number[]>([]);
  const rightHandPitches = signal<readonly number[]>([]);

  /** Point the fake cursor at a step expecting these pitches. */
  function expectPitches(all: number[], rightHand: number[] = all): void {
    pitches.set(all);
    rightHandPitches.set(rightHand);
  }

  beforeEach(() => {
    const cursor = {
      expectedPitches: pitches.asReadonly(),
      expectedPitchesForHand: (hand: 'RIGHT' | 'LEFT') =>
        hand === 'RIGHT'
          ? [...rightHandPitches()]
          : pitches().filter((p) => !rightHandPitches().includes(p)),
    };

    TestBed.configureTestingModule({
      providers: [WaitGateService, { provide: AlignmentCursorService, useValue: cursor }],
    });
    gate = TestBed.inject(WaitGateService);
  });

  it('does not advance until every note of a chord has been played', () => {
    expectPitches([60, 64, 67]);
    gate.arm('BOTH');

    expect(gate.register(60)).toBeFalse();
    expect(gate.register(64)).toBeFalse();
    // Only the third note completes it. A chord is one event to the score and three
    // separate presses to a beginner, and both have to be true at once.
    expect(gate.register(67)).toBeTrue();
  });

  it('accepts the notes of a chord in any order, with any gap between them', () => {
    expectPitches([60, 64, 67]);
    gate.arm('BOTH');

    expect(gate.register(67)).toBeFalse();
    expect(gate.register(60)).toBeFalse();
    expect(gate.register(64)).toBeTrue();
  });

  it('does not punish a wrong note by clearing what was already found', () => {
    expectPitches([60, 64]);
    gate.arm('BOTH');

    gate.register(60);
    expect(gate.register(61)).toBeFalse();      // wrong note, hunting for the next one
    expect(gate.remaining()).toEqual([64]);     // the 60 they found is still theirs
    expect(gate.register(64)).toBeTrue();
  });

  it('ignores a repeated note rather than counting it twice', () => {
    expectPitches([60, 64]);
    gate.arm('BOTH');

    expect(gate.register(60)).toBeFalse();
    // A held key that repeats, or a bounced press, must not complete the chord.
    expect(gate.register(60)).toBeFalse();
    expect(gate.isComplete()).toBeFalse();
  });

  it('waits only for the hand the stage is practising', () => {
    expectPitches([48, 60], [60]);
    gate.arm('RIGHT');

    // The left-hand note is not this stage's problem, so playing it changes nothing...
    expect(gate.register(48)).toBeFalse();
    // ...and the right-hand note alone completes the step.
    expect(gate.register(60)).toBeTrue();
  });

  it('never reports an empty step as complete', () => {
    expectPitches([]);
    gate.arm('BOTH');

    // Otherwise the cursor would run away through every rest in the piece.
    expect(gate.isComplete()).toBeFalse();
  });

  it('accepts any key on a rhythm stage, because pitch is not being asked for', () => {
    expectPitches([60, 64, 67]);
    gate.arm('BOTH');

    expect(gate.registerAnyKey()).toBeTrue();
    expect(gate.remaining()).toEqual([]);
  });

  it('starts each step fresh when re-armed', () => {
    expectPitches([60, 64]);
    gate.arm('BOTH');
    gate.register(60);

    expectPitches([65, 69]);
    gate.arm('BOTH');

    expect(gate.remaining()).toEqual([65, 69]);
    expect(gate.isComplete()).toBeFalse();
  });

  it('reports what is still outstanding, so the UI can highlight it', () => {
    expectPitches([60, 64, 67]);
    gate.arm('BOTH');
    gate.register(64);

    expect(gate.remaining()).toEqual([60, 67]);
    expect(gate.state().satisfied).toEqual([64]);
  });
});
