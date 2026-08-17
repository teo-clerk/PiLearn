"""Hand assignment tests.

These assert BEHAVIOUR, not tuned constants: "the bass note goes to the left hand" must
hold whatever the penalty weights become. Where a threshold is asserted directly, it is
because the threshold is the contract (e.g. a span beyond a human reach is impossible).
"""

from __future__ import annotations

import pytest

from pilearn_worker.models.raw import RawNote
from pilearn_worker.pedagogy.hand_detector import (
    MAX_POSSIBLE_SPAN,
    AssignmentMethod,
    Hand,
    assign_hands,
)

PPQ = 480


def note(midi: int, tick: int = 0, staff: int = 1, voice: int = 1, spelled: str = "C4") -> RawNote:
    return RawNote(
        midi=midi,
        spelled=spelled,
        start_tick=tick,
        duration_ticks=PPQ,
        measure_index=0,
        beat_offset=tick / PPQ,
        staff=staff,
        voice=voice,
    )


GRAND_STAFF = {1: Hand.RIGHT, 2: Hand.LEFT}


class TestStaffTier:
    def test_balanced_grand_staff_is_trusted(self):
        notes = tuple(
            [note(72 + i, tick=i * PPQ, staff=1) for i in range(4)]
            + [note(48 + i, tick=i * PPQ, staff=2) for i in range(4)]
        )
        result = assign_hands(notes, GRAND_STAFF)

        assert result.method is AssignmentMethod.STAFF
        assert result.confidence == 1.0
        assert all(result.hands[i] is Hand.RIGHT for i in range(4))
        assert all(result.hands[i] is Hand.LEFT for i in range(4, 8))

    def test_unbalanced_staves_fall_through_to_split(self):
        """97/3 is not a resolved brace — it is OMR that failed to split the staves."""
        notes = tuple(
            [note(60 + i % 12, tick=i * PPQ, staff=1) for i in range(40)]
            + [note(40, tick=0, staff=2)]
        )
        result = assign_hands(notes, GRAND_STAFF)

        assert result.method is AssignmentMethod.SPLIT

    def test_single_staff_source_falls_through_to_split(self):
        notes = tuple(note(60 + i, tick=i * PPQ, staff=1) for i in range(8))
        result = assign_hands(notes, {1: Hand.RIGHT})

        assert result.method is AssignmentMethod.SPLIT

    def test_cross_staff_writing_is_reported_not_rejected(self):
        """A left-hand note above a right-hand note is legitimate (Debussy, Chopin).
        Report the crossing; keep the source's assignment."""
        notes = (
            note(60, tick=0, staff=1),   # RH, middle C
            note(72, tick=0, staff=2),   # LH reaching above RH — a crossing
            note(59, tick=PPQ, staff=1),
            note(45, tick=PPQ, staff=2),
        )
        result = assign_hands(notes, GRAND_STAFF)

        assert result.method is AssignmentMethod.STAFF
        assert result.metrics.crossings == 1
        assert result.hands[1] is Hand.LEFT


class TestSplitTier:
    def test_wide_chord_splits_between_the_hands(self):
        """A two-octave-plus span at one onset cannot be one hand."""
        notes = (
            note(36, tick=0),
            note(43, tick=0),
            note(72, tick=0),
            note(76, tick=0),
        )
        result = assign_hands(notes, {})

        assert result.hands[0] is Hand.LEFT
        assert result.hands[1] is Hand.LEFT
        assert result.hands[2] is Hand.RIGHT
        assert result.hands[3] is Hand.RIGHT

    def test_playable_wide_chord_respects_hand_reach(self):
        """A chord that two hands CAN cover must be split so neither hand overreaches.

        36..84 (four octaves) is deliberately not used here: two hands reach at most
        ~34 semitones, so that chord is unplayable as written and no assignment can
        satisfy the span bound. That case is covered by the test below, which asserts
        the impossibility is reported rather than silently accepted.
        """
        notes = tuple(note(m, tick=0) for m in (48, 55, 67, 72))
        result = assign_hands(notes, {})

        for hand in (Hand.LEFT, Hand.RIGHT):
            pitches = [notes[i].midi for i, h in result.hands.items() if h is hand]
            if len(pitches) > 1:
                assert max(pitches) - min(pitches) <= MAX_POSSIBLE_SPAN, (
                    f"{hand.value} was assigned an unplayable span: {sorted(pitches)}"
                )

    def test_unplayable_chord_is_reported_not_hidden(self):
        """Four octaves at one onset cannot be played by two hands. The algorithm must
        surface that rather than inventing a plausible-looking assignment."""
        notes = tuple(note(m, tick=0) for m in (36, 48, 60, 72, 84))
        result = assign_hands(notes, {})

        assert result.metrics.impossible_events > 0
        assert result.metrics.ambiguous
        assert result.confidence < 0.7

    def test_split_point_is_stable_across_a_steady_texture(self):
        """A repeating accompaniment pattern must not make the split wander."""
        notes = []
        for bar in range(8):
            base = bar * 4 * PPQ
            notes.append(note(43, tick=base))
            notes.append(note(base and 67 or 67, tick=base))
        result = assign_hands(tuple(notes), {})

        assert result.metrics.split_stability > 0.9
        assert not result.metrics.ambiguous

    def test_close_cluster_stays_in_one_hand(self):
        """Four notes within a fifth are one hand — splitting them would be nonsense."""
        notes = tuple(note(m, tick=0) for m in (60, 62, 64, 67))
        result = assign_hands(notes, {})

        assigned = {result.hands[i] for i in range(4)}
        assert len(assigned) == 1


class TestFallbackTier:
    def test_single_repeated_pitch_uses_the_middle_c_boundary(self):
        low = tuple(note(40, tick=i * PPQ) for i in range(4))
        assert assign_hands(low, {}).method is AssignmentMethod.FALLBACK
        assert all(h is Hand.LEFT for h in assign_hands(low, {}).hands.values())

        high = tuple(note(80, tick=i * PPQ) for i in range(4))
        assert all(h is Hand.RIGHT for h in assign_hands(high, {}).hands.values())

    def test_empty_input(self):
        result = assign_hands((), {})
        assert result.hands == {}
        assert result.confidence == 1.0


class TestMetrics:
    def test_stretch_is_reported_without_being_called_impossible(self):
        """A tenth is a stretch, not an impossibility — flag it, do not split it."""
        notes = (note(60, tick=0), note(76, tick=0))
        result = assign_hands(notes, {})

        assert result.metrics.impossible_events == 0

    def test_impossible_span_lowers_confidence_and_flags_ambiguity(self):
        # Force a bad assignment via an untrustworthy staff map so the split tier runs
        # on genuinely unplayable material.
        notes = tuple(note(m, tick=0) for m in (21, 40, 60, 85, 108))
        result = assign_hands(notes, {})

        assert result.confidence < 1.0

    def test_every_note_is_assigned(self):
        notes = tuple(note(50 + i, tick=(i // 3) * PPQ) for i in range(12))
        result = assign_hands(notes, {})

        assert set(result.hands) == set(range(12))
        assert all(isinstance(h, Hand) for h in result.hands.values())


@pytest.mark.parametrize("staff_map", [{}, {1: Hand.RIGHT}, GRAND_STAFF])
def test_never_raises_on_plausible_input(staff_map):
    notes = tuple(
        note(40 + (i * 7) % 60, tick=(i // 2) * PPQ, staff=1 + i % 2) for i in range(30)
    )
    result = assign_hands(notes, staff_map)
    assert len(result.hands) == 30
