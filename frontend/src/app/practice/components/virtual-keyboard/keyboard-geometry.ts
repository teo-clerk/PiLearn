/**
 * Piano keyboard geometry — pure functions, no Angular, no DOM.
 *
 * Split out so the layout maths is unit-testable without rendering anything. Getting
 * black-key placement subtly wrong is easy and produces a keyboard that looks almost
 * right, which is worse than one that looks obviously wrong.
 */

/** Pitch classes struck by a white key, in octave order: C D E F G A B. */
const WHITE_PITCH_CLASSES = [0, 2, 4, 5, 7, 9, 11] as const;

/** Note letters indexed by pitch class; black keys take the sharp spelling. */
const PITCH_CLASS_NAMES = [
  'C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B',
] as const;

/** Standard 88-key piano: A0 (21) to C8 (108). */
export const MIDI_A0 = 21;
export const MIDI_C8 = 108;

/**
 * White-key widths a black key is offset from the boundary between its neighbours.
 *
 * On a real piano black keys are NOT centred on the white-key boundary — the three-key
 * group (F#/G#/A#) and the two-key group (C#/D#) are each nudged outward so the white
 * key tails below them stay playable. These are the conventional offsets.
 */
const BLACK_KEY_OFFSET: Record<number, number> = {
  1: -0.10,  // C#  pulled left
  3: 0.10,   // D#  pushed right
  6: -0.13,  // F#
  8: 0.0,    // G#  centred
  10: 0.13,  // A#
};

export interface KeyLayout {
  midi: number;
  /** Scientific pitch notation with the sharp spelling, e.g. "C#4". */
  name: string;
  isBlack: boolean;
  x: number;
  width: number;
  height: number;
  /** Black keys paint over white ones. */
  zIndex: number;
}

export interface KeyboardLayout {
  keys: KeyLayout[];
  width: number;
  height: number;
  whiteKeyCount: number;
}

export function isBlackKey(midi: number): boolean {
  return !WHITE_PITCH_CLASSES.includes((midi % 12) as (typeof WHITE_PITCH_CLASSES)[number]);
}

/** MIDI number to scientific pitch notation. C4 = 60 (middle C). */
export function midiToName(midi: number): string {
  const octave = Math.floor(midi / 12) - 1;
  return `${PITCH_CLASS_NAMES[midi % 12]}${octave}`;
}

/**
 * Parse scientific pitch notation to a MIDI number.
 *
 * Accepts both accidental conventions: '#'/'s' for sharps and 'b'/'-' for flats.
 * music21 emits flats as '-' (e.g. "B-4"), and the legacy keyboard service passes
 * names straight through from `@tonejs/midi`, so both reach this function.
 */
export function nameToMidi(name: string): number | null {
  const match = /^([A-Ga-g])([#s]{0,2}|[b-]{0,2})(-?\d)$/.exec(name.trim());
  if (!match) return null;

  const [, letter, accidental, octaveText] = match;
  const base = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 }[letter.toUpperCase()];
  if (base === undefined) return null;

  let shift = 0;
  for (const character of accidental) {
    if (character === '#' || character === 's') shift += 1;
    else if (character === 'b' || character === '-') shift -= 1;
  }

  const midi = (Number(octaveText) + 1) * 12 + base + shift;
  return midi >= 0 && midi <= 127 ? midi : null;
}

/** Count white keys in [lowest, highest] inclusive. */
export function countWhiteKeys(lowest: number, highest: number): number {
  let count = 0;
  for (let midi = lowest; midi <= highest; midi++) {
    if (!isBlackKey(midi)) count++;
  }
  return count;
}

/**
 * Build the full key layout for a MIDI range.
 *
 * The range is widened to start and end on white keys — a keyboard beginning on a black
 * key has nothing to hang it off and renders as a floating sliver.
 */
export function buildKeyboardLayout(
  lowestMidi: number,
  highestMidi: number,
  whiteKeyWidth: number,
  whiteKeyHeight: number,
): KeyboardLayout {
  let lowest = Math.max(0, Math.min(lowestMidi, highestMidi));
  let highest = Math.min(127, Math.max(lowestMidi, highestMidi));

  while (lowest > 0 && isBlackKey(lowest)) lowest--;
  while (highest < 127 && isBlackKey(highest)) highest++;

  const blackKeyWidth = whiteKeyWidth * 0.62;
  const blackKeyHeight = whiteKeyHeight * 0.62;

  const keys: KeyLayout[] = [];
  let whiteIndex = 0;

  for (let midi = lowest; midi <= highest; midi++) {
    const black = isBlackKey(midi);

    if (!black) {
      keys.push({
        midi,
        name: midiToName(midi),
        isBlack: false,
        x: whiteIndex * whiteKeyWidth,
        width: whiteKeyWidth,
        height: whiteKeyHeight,
        zIndex: 0,
      });
      whiteIndex++;
      continue;
    }

    // A black key sits on the boundary after the white key that precedes it, nudged by
    // the conventional offset for its position in the group.
    const offset = BLACK_KEY_OFFSET[midi % 12] ?? 0;
    keys.push({
      midi,
      name: midiToName(midi),
      isBlack: true,
      x: whiteIndex * whiteKeyWidth - blackKeyWidth / 2 + offset * whiteKeyWidth,
      width: blackKeyWidth,
      height: blackKeyHeight,
      zIndex: 1,
    });
  }

  // White keys first so black keys paint over them: SVG has no z-index, only document
  // order.
  keys.sort((a, b) => a.zIndex - b.zIndex || a.midi - b.midi);

  return {
    keys,
    width: whiteIndex * whiteKeyWidth,
    height: whiteKeyHeight,
    whiteKeyCount: whiteIndex,
  };
}
