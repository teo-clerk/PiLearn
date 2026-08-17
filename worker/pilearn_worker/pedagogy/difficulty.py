"""Per-measure difficulty analysis and practice chunking.

Implements PRODUCT_SPEC.md §5.4 (ten normalised features -> a 0..10 scalar) and §5.1
(phrase-aware chunking).

Two properties matter more than the exact numbers:

  * MONOTONICITY. Denser, faster, wider, more chromatic music must score higher. The
    tests assert ordering between synthetic measures, not absolute values — absolute
    calibration comes from fitting against the syllabus classifier (P3-T02) and is
    expected to change; the ordering is not.

  * VERSIONING. Every score carries `weights_version`. Changing weights bumps it and
    marks existing plans as *offerable for regeneration* — never silently rewritten
    under a learner mid-piece.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum

from pilearn_worker.models.raw import RawMeasure, RawNote, RawRest
from pilearn_worker.pedagogy.hand_detector import Hand

WEIGHTS_VERSION = "difficulty-2026.08-uncalibrated"

# Feature weights. Sum to 1.0 so the scalar lands in 0..10 after scaling.
# UNCALIBRATED: these are informed priors, not fitted values. P3-T02 fits them against
# piano-syllabus-classifier grades over the fixture corpus and bumps WEIGHTS_VERSION.
WEIGHTS: dict[str, float] = {
    "note_density": 0.16,
    "min_ioi": 0.14,
    "max_span": 0.10,
    "polyphony": 0.11,
    "hand_independence": 0.15,
    "accidental_rate": 0.08,
    "leap_size": 0.09,
    "rhythm_complexity": 0.10,
    "position_shifts": 0.04,
    "ornament_count": 0.03,
}

# Normalisation reference points: the value treated as 1.0 (maximum difficulty).
REF_NOTES_PER_BEAT = 8.0        # 32nd notes in both hands
REF_MIN_IOI_BEATS = 0.0625      # a 64th note
REF_SPAN_SEMITONES = 17.0       # an eleventh
REF_POLYPHONY = 4.0             # four simultaneous voices per hand
REF_LEAP_SEMITONES = 24.0       # two octaves
REF_ORNAMENTS = 4.0
REF_POSITION_SHIFTS = 6.0

# Chunking
MIN_CHUNK_MEASURES = 2
MAX_CHUNK_MEASURES = 8
CHUNK_SIZE_NUMERATOR = 12.0     # target = clamp(12 / mean_difficulty, 2, 8)
HARD_MEASURE_RATIO = 2.0        # isolate a bar this many times the chunk mean
BOUNDARY_TOLERANCE = 2          # measures either side of target to snap to a phrase end

# Boundaries strong enough to end a chunk even mid-window: the phrase is unambiguously
# over, so continuing would make the chunk span two phrases.
STRONG_BOUNDARIES = frozenset({"REST", "DOUBLE_BARLINE", "REPEAT"})


class TechnicalPattern(str, Enum):
    SCALE_RUN = "SCALE_RUN"
    ARPEGGIO = "ARPEGGIO"
    BROKEN_CHORD = "BROKEN_CHORD"
    OCTAVE_LEAP = "OCTAVE_LEAP"
    TRILL = "TRILL"
    CROSS_HAND = "CROSS_HAND"
    SYNCOPATION = "SYNCOPATION"
    POLYRHYTHM = "POLYRHYTHM"


@dataclass(frozen=True, slots=True)
class MeasureFeatures:
    note_density: float = 0.0
    min_ioi: float = 0.0
    max_span: float = 0.0
    polyphony: float = 0.0
    hand_independence: float = 0.0
    accidental_rate: float = 0.0
    leap_size: float = 0.0
    rhythm_complexity: float = 0.0
    position_shifts: float = 0.0
    ornament_count: float = 0.0

    def as_dict(self) -> dict[str, float]:
        return {
            "note_density": self.note_density,
            "min_ioi": self.min_ioi,
            "max_span": self.max_span,
            "polyphony": self.polyphony,
            "hand_independence": self.hand_independence,
            "accidental_rate": self.accidental_rate,
            "leap_size": self.leap_size,
            "rhythm_complexity": self.rhythm_complexity,
            "position_shifts": self.position_shifts,
            "ornament_count": self.ornament_count,
        }


@dataclass(frozen=True, slots=True)
class MeasureAnalysis:
    measure_index: int
    features: MeasureFeatures
    score: float                        # 0..10
    patterns: tuple[TechnicalPattern, ...]
    weights_version: str = WEIGHTS_VERSION


@dataclass(frozen=True, slots=True)
class Chunk:
    ordinal: int
    start_measure: int
    end_measure: int
    difficulty: float
    kind: str                           # PRIMARY | MICRO
    label: str
    boundary_reason: str

    @property
    def measure_count(self) -> int:
        return self.end_measure - self.start_measure + 1


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def _normalise(value: float, reference: float) -> float:
    return _clamp01(value / reference) if reference else 0.0


def _normalise_inverse(value: float, reference: float) -> float:
    """Smaller is harder — used for inter-onset interval."""
    if value <= 0:
        return 1.0
    return _clamp01(reference / value)


# ─────────────────────────────────────────────────────────────────────────────
# Feature extraction
# ─────────────────────────────────────────────────────────────────────────────

def _onset_grid(notes: list[RawNote], ticks_per_beat: float) -> list[float]:
    return sorted({n.start_tick / ticks_per_beat for n in notes})


def _hand_independence(
    right: list[RawNote], left: list[RawNote], ticks_per_beat: float
) -> float:
    """Rhythmic dissimilarity between the hands, 0 (identical) .. 1 (disjoint).

    Jaccard distance over onset sets. Two hands striking together on every beat is the
    easiest case; fully interleaved onsets is the hardest.
    """
    if not right or not left:
        return 0.0

    right_onsets = set(_onset_grid(right, ticks_per_beat))
    left_onsets = set(_onset_grid(left, ticks_per_beat))
    union = right_onsets | left_onsets
    if not union:
        return 0.0
    shared = right_onsets & left_onsets
    return _clamp01(1.0 - len(shared) / len(union))


def _rhythm_complexity(
    notes: list[RawNote], measure: RawMeasure, ticks_per_beat: float
) -> float:
    """Duration-distribution entropy, plus explicit syncopation and tuplet penalties."""
    if not notes:
        return 0.0

    durations: dict[int, int] = {}
    for note in notes:
        durations[note.duration_ticks] = durations.get(note.duration_ticks, 0) + 1

    total = sum(durations.values())
    entropy = -sum(
        (count / total) * math.log2(count / total) for count in durations.values()
    )
    # 3 bits ~ 8 distinct durations in one bar: already very complex.
    entropy_component = _clamp01(entropy / 3.0)

    off_beat = sum(1 for n in notes if (n.start_tick / ticks_per_beat) % 1.0 > 1e-6)
    syncopation = _clamp01(off_beat / len(notes))

    # A tuplet shows up as a duration that does not divide the beat in powers of two.
    tuplet = 0.0
    for duration in durations:
        beats = duration / ticks_per_beat
        if beats > 0:
            log2 = math.log2(beats)
            if abs(log2 - round(log2)) > 0.08:
                tuplet = 0.3
                break

    return _clamp01(0.5 * entropy_component + 0.35 * syncopation + tuplet)


def _accidental_rate(notes: list[RawNote], key_fifths: int) -> float:
    """Share of notes outside the prevailing key signature.

    Derived from the SPELLING, not the MIDI number: an F# in G major is diatonic and
    trivial; the same pitch spelled Gb in G major is a genuine accidental.
    """
    if not notes:
        return 0.0

    # Pitch classes of the major scale for a given sharp count.
    sharps = ["F", "C", "G", "D", "A", "E", "B"]
    flats = ["B", "E", "A", "D", "G", "C", "F"]
    if key_fifths >= 0:
        altered = {name: "#" for name in sharps[:key_fifths]}
    else:
        altered = {name: "b" for name in flats[: abs(key_fifths)]}

    outside = 0
    for note in notes:
        letter = note.spelled[0]
        accidental = "".join(c for c in note.spelled[1:] if c in "#b")
        expected = altered.get(letter, "")
        if accidental != expected:
            outside += 1

    return _clamp01(outside / len(notes))


def _position_shifts(notes: list[RawNote]) -> float:
    """Count hand-position changes: consecutive same-hand onsets moving beyond a reach."""
    if len(notes) < 2:
        return 0.0
    ordered = sorted(notes, key=lambda n: n.start_tick)
    shifts = sum(
        1
        for a, b in zip(ordered, ordered[1:])
        if abs(b.midi - a.midi) > 9 and b.start_tick > a.start_tick
    )
    return _normalise(float(shifts), REF_POSITION_SHIFTS)


def _detect_patterns(
    right: list[RawNote], left: list[RawNote], features: MeasureFeatures
) -> tuple[TechnicalPattern, ...]:
    found: list[TechnicalPattern] = []

    for hand_notes in (right, left):
        if len(hand_notes) < 4:
            continue
        ordered = sorted(hand_notes, key=lambda n: n.start_tick)
        intervals = [b.midi - a.midi for a, b in zip(ordered, ordered[1:])]
        if not intervals:
            continue

        stepwise = sum(1 for i in intervals if 0 < abs(i) <= 2)
        thirds_and_fourths = sum(1 for i in intervals if 3 <= abs(i) <= 5)

        if stepwise / len(intervals) > 0.75 and TechnicalPattern.SCALE_RUN not in found:
            found.append(TechnicalPattern.SCALE_RUN)
        if thirds_and_fourths / len(intervals) > 0.6 and TechnicalPattern.ARPEGGIO not in found:
            found.append(TechnicalPattern.ARPEGGIO)
        if any(abs(i) >= 12 for i in intervals) and TechnicalPattern.OCTAVE_LEAP not in found:
            found.append(TechnicalPattern.OCTAVE_LEAP)

        # A trill: rapid alternation between two adjacent pitches.
        if len(ordered) >= 4:
            pitches = [n.midi for n in ordered]
            alternating = all(
                pitches[i] == pitches[i + 2] for i in range(len(pitches) - 2)
            )
            if alternating and len(set(pitches)) == 2 and max(pitches) - min(pitches) <= 2:
                found.append(TechnicalPattern.TRILL)

    if features.hand_independence > 0.7:
        found.append(TechnicalPattern.POLYRHYTHM)
    if features.rhythm_complexity > 0.55:
        found.append(TechnicalPattern.SYNCOPATION)
    if right and left and min(n.midi for n in right) < max(n.midi for n in left):
        found.append(TechnicalPattern.CROSS_HAND)

    return tuple(dict.fromkeys(found))


def analyse_measure(
    measure: RawMeasure,
    notes: list[RawNote],
    hands: dict[int, Hand],
    ppq: int,
) -> MeasureAnalysis:
    """Compute the feature vector and scalar difficulty for one measure.

    Args:
        measure: the bar.
        notes: notes belonging to it.
        hands: note index (within `notes`) -> hand.
        ppq: ticks per quarter note.
    """
    if not notes:
        return MeasureAnalysis(measure.index, MeasureFeatures(), 0.0, ())

    # Beat length in ticks depends on the denominator: in 6/8 the beat is an eighth.
    ticks_per_beat = ppq * 4.0 / measure.time_denominator
    beats = measure.time_numerator or 1

    right = [n for i, n in enumerate(notes) if hands.get(i, Hand.RIGHT) is Hand.RIGHT]
    left = [n for i, n in enumerate(notes) if hands.get(i, Hand.RIGHT) is Hand.LEFT]

    onsets = _onset_grid(notes, ticks_per_beat)
    if len(onsets) > 1:
        min_ioi_beats = min(b - a for a, b in zip(onsets, onsets[1:]))
    else:
        min_ioi_beats = float(beats)

    span = 0
    onset_buckets: dict[int, list[int]] = {}
    for note in notes:
        onset_buckets.setdefault(note.start_tick, []).append(note.midi)
    for pitches in onset_buckets.values():
        span = max(span, max(pitches) - min(pitches))

    max_simultaneous = max(
        (len(pitches) for pitches in onset_buckets.values()), default=0
    )

    leaps: list[int] = []
    for hand_notes in (right, left):
        ordered = sorted(hand_notes, key=lambda n: n.start_tick)
        leaps.extend(
            abs(b.midi - a.midi)
            for a, b in zip(ordered, ordered[1:])
            if abs(b.midi - a.midi) > 2
        )

    features = MeasureFeatures(
        note_density=_normalise(len(notes) / beats, REF_NOTES_PER_BEAT),
        min_ioi=_normalise_inverse(min_ioi_beats, REF_MIN_IOI_BEATS),
        max_span=_normalise(float(span), REF_SPAN_SEMITONES),
        polyphony=_normalise(float(max_simultaneous), REF_POLYPHONY),
        hand_independence=_hand_independence(right, left, ticks_per_beat),
        accidental_rate=_accidental_rate(notes, measure.key_fifths),
        leap_size=_normalise(
            sum(leaps) / len(leaps) if leaps else 0.0, REF_LEAP_SEMITONES
        ),
        rhythm_complexity=_rhythm_complexity(notes, measure, ticks_per_beat),
        position_shifts=max(_position_shifts(right), _position_shifts(left)),
        ornament_count=_normalise(
            float(sum(1 for n in notes if n.is_ornament or n.is_grace)), REF_ORNAMENTS
        ),
    )

    values = features.as_dict()
    score = 10.0 * sum(WEIGHTS[name] * values[name] for name in WEIGHTS)

    return MeasureAnalysis(
        measure_index=measure.index,
        features=features,
        score=round(max(0.0, min(10.0, score)), 3),
        patterns=_detect_patterns(right, left, features),
    )


# ─────────────────────────────────────────────────────────────────────────────
# Chunking
# ─────────────────────────────────────────────────────────────────────────────

def _phrase_boundaries(
    measures: list[RawMeasure],
    rests_by_measure: dict[int, list[RawRest]],
    ppq: int,
) -> dict[int, str]:
    """Measures after which a phrase plausibly ends, with the reason.

    Deliberately conservative — a false boundary produces a musically wrong chunk, which
    is worse than a chunk that is slightly too long.
    """
    boundaries: dict[int, str] = {}

    for measure in measures:
        if measure.has_double_barline:
            boundaries[measure.index] = "DOUBLE_BARLINE"
            continue
        if measure.ends_repeat or measure.volta is not None:
            boundaries[measure.index] = "REPEAT"
            continue

        # A rest covering at least half the bar reads as a phrase ending.
        rests = rests_by_measure.get(measure.index, [])
        if rests and measure.duration_ticks > 0:
            longest = max(r.duration_ticks for r in rests)
            if longest >= measure.duration_ticks * 0.5:
                boundaries[measure.index] = "REST"

    if measures:
        boundaries[measures[-1].index] = "END"

    return boundaries


def build_chunks(
    measures: list[RawMeasure],
    analyses: dict[int, MeasureAnalysis],
    rests_by_measure: dict[int, list[RawRest]] | None = None,
    cadence_measures: dict[int, str] | None = None,
) -> list[Chunk]:
    """Segment the score into practice chunks (PRODUCT_SPEC §5.1).

    Rule that governs everything: never cut mid-phrase to hit a round number. A 5-bar
    phrase is a 5-bar chunk.
    """
    if not measures:
        return []

    ordered = sorted(measures, key=lambda m: m.index)
    boundaries = _phrase_boundaries(ordered, rests_by_measure or {}, ppq=1)
    for index, reason in (cadence_measures or {}).items():
        boundaries.setdefault(index, reason)

    scores = [analyses[m.index].score if m.index in analyses else 0.0 for m in ordered]
    mean_difficulty = sum(scores) / len(scores) if scores else 1.0
    target = CHUNK_SIZE_NUMERATOR / max(mean_difficulty, 0.5)
    target_size = int(max(MIN_CHUNK_MEASURES, min(MAX_CHUNK_MEASURES, round(target))))

    chunks: list[Chunk] = []
    start = 0
    ordinal = 0

    while start < len(ordered):
        ideal_end = min(start + target_size - 1, len(ordered) - 1)

        # Snap to the nearest phrase boundary within tolerance.
        chosen_end, reason = ideal_end, "SIZE"
        best_distance = BOUNDARY_TOLERANCE + 1
        for candidate in range(
            max(start, ideal_end - BOUNDARY_TOLERANCE),
            min(len(ordered) - 1, ideal_end + BOUNDARY_TOLERANCE) + 1,
        ):
            measure_index = ordered[candidate].index
            if measure_index in boundaries:
                distance = abs(candidate - ideal_end)
                if distance < best_distance:
                    chosen_end, reason, best_distance = candidate, boundaries[measure_index], distance

        # A strong boundary strictly INSIDE the window ends the chunk there, even when
        # it sits outside the snap tolerance. A bar of rest or a double barline means the
        # phrase is over; carrying on to hit a target chunk size would span two phrases,
        # which is the one thing chunking must never do.
        for candidate in range(start, min(chosen_end, len(ordered) - 1)):
            measure_index = ordered[candidate].index
            boundary = boundaries.get(measure_index)
            if boundary in STRONG_BOUNDARIES and candidate - start + 1 >= MIN_CHUNK_MEASURES:
                chosen_end, reason = candidate, boundary
                break

        window = ordered[start : chosen_end + 1]
        window_scores = [
            analyses[m.index].score if m.index in analyses else 0.0 for m in window
        ]
        window_mean = sum(window_scores) / len(window_scores) if window_scores else 0.0

        # Isolate a bar that dominates its neighbours — it needs its own stage ladder.
        hard_local = None
        if len(window) > 1 and window_mean > 0:
            for offset, score in enumerate(window_scores):
                if score > window_mean * HARD_MEASURE_RATIO:
                    hard_local = offset
                    break

        if hard_local is not None and len(window) > 1:
            if hard_local > 0:
                before = ordered[start : start + hard_local]
                chunks.append(_make_chunk(ordinal, before, analyses, "PRIMARY", "SIZE"))
                ordinal += 1

            hard_measure = ordered[start + hard_local]
            chunks.append(
                _make_chunk(ordinal, [hard_measure], analyses, "MICRO", "HARD_MEASURE")
            )
            ordinal += 1
            start = start + hard_local + 1
            continue

        chunks.append(_make_chunk(ordinal, window, analyses, "PRIMARY", reason))
        ordinal += 1
        start = chosen_end + 1

    return _merge_trivial_singletons(chunks, analyses)


def _make_chunk(
    ordinal: int,
    window: list[RawMeasure],
    analyses: dict[int, MeasureAnalysis],
    kind: str,
    reason: str,
) -> Chunk:
    scores = [analyses[m.index].score if m.index in analyses else 0.0 for m in window]
    difficulty = sum(scores) / len(scores) if scores else 0.0
    first, last = window[0], window[-1]
    label = (
        f"Bar {first.number}"
        if first.index == last.index
        else f"Bars {first.number}-{last.number}"
    )
    return Chunk(
        ordinal=ordinal,
        start_measure=first.index,
        end_measure=last.index,
        difficulty=round(difficulty, 3),
        kind=kind,
        label=label,
        boundary_reason=reason,
    )


def _merge_trivial_singletons(
    chunks: list[Chunk], analyses: dict[int, MeasureAnalysis]
) -> list[Chunk]:
    """Fold an easy one-bar PRIMARY chunk into its neighbour.

    MICRO chunks are never merged — they exist precisely because they are hard.
    """
    if len(chunks) < 2:
        return chunks

    all_scores = [a.score for a in analyses.values()]
    if not all_scores:
        return chunks
    ordered_scores = sorted(all_scores)
    median_score = ordered_scores[len(ordered_scores) // 2]

    merged: list[Chunk] = []
    for chunk in chunks:
        trivial = (
            chunk.measure_count == 1
            and chunk.kind == "PRIMARY"
            and chunk.difficulty <= median_score
        )
        if trivial and merged and merged[-1].kind == "PRIMARY":
            previous = merged[-1]
            combined = previous.measure_count + chunk.measure_count
            if combined <= MAX_CHUNK_MEASURES:
                merged[-1] = Chunk(
                    ordinal=previous.ordinal,
                    start_measure=previous.start_measure,
                    end_measure=chunk.end_measure,
                    difficulty=round(
                        (previous.difficulty * previous.measure_count + chunk.difficulty)
                        / combined,
                        3,
                    ),
                    kind="PRIMARY",
                    label=f"Bars {previous.start_measure + 1}-{chunk.end_measure + 1}",
                    boundary_reason=chunk.boundary_reason,
                )
                continue
        merged.append(chunk)

    return [
        Chunk(
            ordinal=index,
            start_measure=c.start_measure,
            end_measure=c.end_measure,
            difficulty=c.difficulty,
            kind=c.kind,
            label=c.label,
            boundary_reason=c.boundary_reason,
        )
        for index, c in enumerate(merged)
    ]
