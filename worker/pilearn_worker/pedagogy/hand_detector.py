"""Hand assignment for piano scores.

Ported from the deleted `frontend/src/app/desktop/service/hand-detector.service.ts`
(recoverable at `git show 00a4c49:frontend/src/app/desktop/service/hand-detector.service.ts`),
hardened and made testable. Roadmap task P1-T14.

The problem
-----------
Grand-staff notation *usually* says which hand plays what: staff 1 is the right hand,
staff 2 the left. Two cases break that:

  * Cross-staff writing — a single voice notated across both staves, common in Debussy,
    Chopin and most impressionist repertoire. `clair-de-lune` is in the fixture corpus
    precisely for this.
  * Single-staff sources — OMR output that failed to resolve the brace, or lead-sheet
    style input where everything landed on one staff.

Strategy
--------
Three tiers, most-trustworthy first:

  1. STAFF     — the source says so, and the staves are balanced. Trust it.
  2. SPLIT     — one staff, or wildly unbalanced staves. Find a pitch split point per
                 onset group, penalising hand span, split instability and crossings.
  3. FALLBACK  — degenerate input (single note, no structure). Middle C boundary.

Tier 2 is where the real work is. It is a per-onset-group decision with a penalty over
the whole sequence, not an independent per-note threshold: a good split point moves with
the music, and stability between adjacent groups matters more than optimality at any one.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from statistics import median

from pilearn_worker.models.raw import RawNote

# Physiological constants. An octave-and-a-fourth is a generous but real adult reach;
# beyond it, notes almost certainly belong to different hands.
MAX_COMFORTABLE_SPAN = 12       # semitones — an octave, playable by most adults
MAX_POSSIBLE_SPAN = 17          # semitones — an eleventh, the practical upper bound
MIDDLE_C = 60

# Penalty weights for split-point selection. Tuned so that span violations dominate:
# an unplayable assignment is always worse than an inelegant one.
PENALTY_SPAN_EXCEEDED = 100.0   # per semitone beyond MAX_POSSIBLE_SPAN
PENALTY_SPAN_STRETCH = 4.0      # per semitone beyond MAX_COMFORTABLE_SPAN
PENALTY_SPLIT_MOVE = 1.5        # per semitone the split point moves between groups
PENALTY_EMPTY_HAND = 0.5        # mild — one-handed passages are legitimate
PENALTY_CROSSING = 8.0          # left-hand note above a right-hand note

# Splitting a group that comfortably fits one hand is almost always wrong: a four-note
# chord inside an octave is one hand, not two. Without this, the split-move penalty —
# anchored on the median of all pitches, which for a close cluster sits INSIDE it —
# happily bisects the cluster because that keeps the boundary closest to the anchor.
PENALTY_SPLIT_COHESIVE = 25.0

# A staff is considered "real" if it carries at least this share of the notes.
# Below it, the source's staff assignment is not trustworthy.
MIN_STAFF_SHARE = 0.05


class Hand(str, Enum):
    RIGHT = "RIGHT"
    LEFT = "LEFT"


class AssignmentMethod(str, Enum):
    STAFF = "STAFF"
    SPLIT = "SPLIT"
    FALLBACK = "FALLBACK"


@dataclass(frozen=True, slots=True)
class HandMetrics:
    """Reported alongside the assignment, and consumed by the difficulty analyser."""

    crossings: int                  # onsets where the left hand sounds above the right
    max_right_span: int             # semitones
    max_left_span: int
    stretch_events: int             # onsets requiring more than a comfortable span
    impossible_events: int          # onsets exceeding any plausible reach
    mean_split_point: float
    split_stability: float          # 0..1; 1.0 = the split never moved
    ambiguous: bool                 # true when the result should be surfaced for review


@dataclass(frozen=True, slots=True)
class HandAssignment:
    hands: dict[int, Hand]          # index into the input note tuple -> hand
    method: AssignmentMethod
    metrics: HandMetrics
    confidence: float               # 0..1


@dataclass(frozen=True, slots=True)
class _OnsetGroup:
    """Notes sounding together, in pitch order."""

    tick: int
    indices: tuple[int, ...]
    pitches: tuple[int, ...]


def _group_by_onset(notes: tuple[RawNote, ...]) -> list[_OnsetGroup]:
    buckets: dict[int, list[int]] = {}
    for index, note in enumerate(notes):
        buckets.setdefault(note.start_tick, []).append(index)

    groups: list[_OnsetGroup] = []
    for tick in sorted(buckets):
        indices = sorted(buckets[tick], key=lambda i: notes[i].midi)
        groups.append(
            _OnsetGroup(
                tick=tick,
                indices=tuple(indices),
                pitches=tuple(notes[i].midi for i in indices),
            )
        )
    return groups


def _span(pitches: tuple[int, ...]) -> int:
    return max(pitches) - min(pitches) if len(pitches) > 1 else 0


def _split_penalty(
    pitches: tuple[int, ...], split_index: int, previous_split: float | None
) -> float:
    """Cost of cutting this onset group into left = [:split_index], right = [split_index:].

    Pitches arrive ascending, so the split is a single index rather than a set partition:
    on a keyboard the left hand plays the lower notes. Hand-crossing is handled by the
    caller as a separate, explicitly-detected event.
    """
    left, right = pitches[:split_index], pitches[split_index:]
    penalty = 0.0

    # A group that fits one hand should stay in one hand.
    if left and right and _span(pitches) <= MAX_COMFORTABLE_SPAN:
        penalty += PENALTY_SPLIT_COHESIVE

    for group in (left, right):
        if len(group) < 2:
            continue
        span = _span(group)
        if span > MAX_POSSIBLE_SPAN:
            penalty += PENALTY_SPAN_EXCEEDED * (span - MAX_POSSIBLE_SPAN)
        elif span > MAX_COMFORTABLE_SPAN:
            penalty += PENALTY_SPAN_STRETCH * (span - MAX_COMFORTABLE_SPAN)

    if not left or not right:
        penalty += PENALTY_EMPTY_HAND

    if previous_split is not None:
        boundary = (
            (left[-1] + right[0]) / 2 if left and right
            else (right[0] if right else left[-1])
        )
        penalty += PENALTY_SPLIT_MOVE * abs(boundary - previous_split)

    return penalty


def _best_split(pitches: tuple[int, ...], previous_split: float | None) -> tuple[int, float]:
    best_index, best_cost = 0, float("inf")
    for split_index in range(len(pitches) + 1):
        cost = _split_penalty(pitches, split_index, previous_split)
        if cost < best_cost:
            best_index, best_cost = split_index, cost
    return best_index, best_cost


def _boundary_of(pitches: tuple[int, ...], split_index: int) -> float:
    left, right = pitches[:split_index], pitches[split_index:]
    if left and right:
        return (left[-1] + right[0]) / 2
    if right:
        return right[0] - 0.5
    return left[-1] + 0.5


def _staff_assignment_is_trustworthy(
    notes: tuple[RawNote, ...], staff_to_hand: dict[int, Hand]
) -> bool:
    """Decide whether to believe the source's staff numbers.

    Two staves carrying a plausible share of the notes each means the brace was resolved
    and we should not second-guess it. One staff, or a 97/3 split, means it was not.
    """
    if len(staff_to_hand) < 2:
        return False

    counts: dict[int, int] = {}
    for note in notes:
        counts[note.staff] = counts.get(note.staff, 0) + 1

    known = {s: c for s, c in counts.items() if s in staff_to_hand}
    if len(known) < 2:
        return False

    total = sum(known.values())
    return all(count / total >= MIN_STAFF_SHARE for count in known.values())


def _measure_metrics(
    notes: tuple[RawNote, ...],
    hands: dict[int, Hand],
    groups: list[_OnsetGroup],
    split_points: list[float],
) -> HandMetrics:
    crossings = 0
    stretch_events = 0
    impossible_events = 0
    max_right = 0
    max_left = 0

    for group in groups:
        right = [notes[i].midi for i in group.indices if hands[i] is Hand.RIGHT]
        left = [notes[i].midi for i in group.indices if hands[i] is Hand.LEFT]

        if right and left and min(right) < max(left):
            crossings += 1

        for pitches, is_right in ((right, True), (left, False)):
            if len(pitches) < 2:
                continue
            span = max(pitches) - min(pitches)
            if is_right:
                max_right = max(max_right, span)
            else:
                max_left = max(max_left, span)
            if span > MAX_POSSIBLE_SPAN:
                impossible_events += 1
            elif span > MAX_COMFORTABLE_SPAN:
                stretch_events += 1

    if split_points:
        mean_split = sum(split_points) / len(split_points)
        if len(split_points) > 1:
            movement = sum(
                abs(b - a) for a, b in zip(split_points, split_points[1:])
            ) / (len(split_points) - 1)
            # 12 semitones of average drift is treated as fully unstable.
            stability = max(0.0, 1.0 - movement / 12.0)
        else:
            stability = 1.0
    else:
        mean_split, stability = float(MIDDLE_C), 1.0

    ambiguous = impossible_events > 0 or stability < 0.5

    return HandMetrics(
        crossings=crossings,
        max_right_span=max_right,
        max_left_span=max_left,
        stretch_events=stretch_events,
        impossible_events=impossible_events,
        mean_split_point=mean_split,
        split_stability=stability,
        ambiguous=ambiguous,
    )


def assign_hands(
    notes: tuple[RawNote, ...],
    staff_to_hand: dict[int, Hand] | None = None,
) -> HandAssignment:
    """Assign every note to a hand.

    Args:
        notes: all notes of one part, any order.
        staff_to_hand: the source's staff->hand mapping, when the parser resolved one.

    Returns:
        A HandAssignment whose `hands` is keyed by index into `notes`.
    """
    if not notes:
        return HandAssignment(
            hands={},
            method=AssignmentMethod.FALLBACK,
            metrics=HandMetrics(0, 0, 0, 0, 0, float(MIDDLE_C), 1.0, False),
            confidence=1.0,
        )

    groups = _group_by_onset(notes)
    staff_to_hand = staff_to_hand or {}

    # ── Tier 1: trust the source ─────────────────────────────────────────────
    if _staff_assignment_is_trustworthy(notes, staff_to_hand):
        hands = {
            index: staff_to_hand.get(note.staff, Hand.RIGHT)
            for index, note in enumerate(notes)
        }
        split_points = []
        for group in groups:
            right = [notes[i].midi for i in group.indices if hands[i] is Hand.RIGHT]
            left = [notes[i].midi for i in group.indices if hands[i] is Hand.LEFT]
            if right and left:
                split_points.append((max(left) + min(right)) / 2)

        metrics = _measure_metrics(notes, hands, groups, split_points)
        # Cross-staff writing shows up here as crossings against a trusted mapping.
        # That is information, not an error — report it, keep the assignment.
        confidence = 1.0 if not metrics.ambiguous else 0.75
        return HandAssignment(hands, AssignmentMethod.STAFF, metrics, confidence)

    # ── Tier 3: degenerate input ─────────────────────────────────────────────
    distinct_pitches = {note.midi for note in notes}
    if len(distinct_pitches) < 2:
        pitch = next(iter(distinct_pitches))
        hand = Hand.RIGHT if pitch >= MIDDLE_C else Hand.LEFT
        hands = {index: hand for index in range(len(notes))}
        metrics = _measure_metrics(notes, hands, groups, [float(MIDDLE_C)])
        return HandAssignment(hands, AssignmentMethod.FALLBACK, metrics, 0.5)

    # ── Tier 2: penalty-driven split ─────────────────────────────────────────
    hands: dict[int, Hand] = {}
    split_points: list[float] = []
    previous_split: float | None = median(note.midi for note in notes)

    for group in groups:
        split_index, _ = _best_split(group.pitches, previous_split)
        for position, note_index in enumerate(group.indices):
            hands[note_index] = Hand.LEFT if position < split_index else Hand.RIGHT

        boundary = _boundary_of(group.pitches, split_index)
        split_points.append(boundary)
        previous_split = boundary

    metrics = _measure_metrics(notes, hands, groups, split_points)

    confidence = 0.85
    if metrics.impossible_events:
        confidence -= 0.25
    if metrics.split_stability < 0.5:
        confidence -= 0.20
    confidence = max(0.1, confidence)

    return HandAssignment(hands, AssignmentMethod.SPLIT, metrics, confidence)
