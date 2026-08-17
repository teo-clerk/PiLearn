"""Difficulty and chunking tests.

The weights are explicitly uncalibrated (P3-T02 fits them against the syllabus
classifier), so these assert ORDERING and INVARIANTS, not absolute values. A test that
pinned `score == 4.7` would fail on the first calibration and teach nobody anything.
"""

from __future__ import annotations

import pytest

from pilearn_worker.models.raw import RawMeasure, RawNote, RawRest
from pilearn_worker.pedagogy.difficulty import (
    MAX_CHUNK_MEASURES,
    MIN_CHUNK_MEASURES,
    TechnicalPattern,
    analyse_measure,
    build_chunks,
)
from pilearn_worker.pedagogy.hand_detector import Hand

PPQ = 480


def measure(index: int = 0, numerator: int = 4, denominator: int = 4, fifths: int = 0, **kw):
    return RawMeasure(
        index=index,
        number=str(index + 1),
        start_tick=index * PPQ * 4,
        end_tick=(index + 1) * PPQ * 4,
        time_numerator=numerator,
        time_denominator=denominator,
        key_fifths=fifths,
        **kw,
    )


def note(midi: int, tick: int, duration: int = PPQ, spelled: str = "C4", **kw) -> RawNote:
    return RawNote(
        midi=midi,
        spelled=spelled,
        start_tick=tick,
        duration_ticks=duration,
        measure_index=0,
        beat_offset=tick / PPQ,
        staff=kw.pop("staff", 1),
        voice=kw.pop("voice", 1),
        **kw,
    )


def all_right(count: int) -> dict[int, Hand]:
    return dict.fromkeys(range(count), Hand.RIGHT)


class TestDifficultyOrdering:
    def test_denser_music_scores_higher(self):
        sparse = [note(60, 0, PPQ * 4, "C4")]
        dense = [note(60 + i, i * PPQ // 4, PPQ // 4, "C4") for i in range(16)]

        sparse_score = analyse_measure(measure(), sparse, all_right(1), PPQ).score
        dense_score = analyse_measure(measure(), dense, all_right(16), PPQ).score

        assert dense_score > sparse_score

    def test_wider_span_scores_higher(self):
        narrow = [note(60, 0, spelled="C4"), note(64, 0, spelled="E4")]
        wide = [note(48, 0, spelled="C3"), note(84, 0, spelled="C6")]

        narrow_score = analyse_measure(measure(), narrow, all_right(2), PPQ).score
        wide_score = analyse_measure(measure(), wide, all_right(2), PPQ).score

        assert wide_score > narrow_score

    def test_chromatic_music_scores_higher_than_diatonic(self):
        # In C major (fifths=0): naturals are diatonic, sharps are not.
        diatonic = [
            note(60, 0, spelled="C4"), note(62, PPQ, spelled="D4"),
            note(64, PPQ * 2, spelled="E4"), note(65, PPQ * 3, spelled="F4"),
        ]
        chromatic = [
            note(61, 0, spelled="C#4"), note(63, PPQ, spelled="D#4"),
            note(66, PPQ * 2, spelled="F#4"), note(68, PPQ * 3, spelled="G#4"),
        ]

        assert (
            analyse_measure(measure(), chromatic, all_right(4), PPQ).score
            > analyse_measure(measure(), diatonic, all_right(4), PPQ).score
        )

    def test_accidental_rate_is_spelling_aware_not_pitch_aware(self):
        """F# in G major (fifths=1) is diatonic. The same MIDI pitch is only an
        accidental when the SPELLING departs from the key signature."""
        in_key = [note(66, 0, spelled="F#4")]
        out_of_key = [note(66, 0, spelled="Gb4")]

        g_major = measure(fifths=1)
        assert analyse_measure(g_major, in_key, all_right(1), PPQ).features.accidental_rate == 0.0
        assert analyse_measure(g_major, out_of_key, all_right(1), PPQ).features.accidental_rate > 0

    def test_independent_hands_score_higher_than_locked_hands(self):
        locked = (
            [note(72, i * PPQ, spelled="C5") for i in range(4)]
            + [note(48, i * PPQ, spelled="C3") for i in range(4)]
        )
        independent = (
            [note(72, i * PPQ, spelled="C5") for i in range(4)]
            + [note(48, i * PPQ + PPQ // 3, spelled="C3") for i in range(4)]
        )
        hands = {**dict.fromkeys(range(4), Hand.RIGHT), **dict.fromkeys(range(4, 8), Hand.LEFT)}

        locked_result = analyse_measure(measure(), locked, hands, PPQ)
        independent_result = analyse_measure(measure(), independent, hands, PPQ)

        assert locked_result.features.hand_independence == 0.0
        assert independent_result.features.hand_independence > 0.9
        assert independent_result.score > locked_result.score


class TestDifficultyBounds:
    def test_score_is_always_within_range(self):
        cases = [
            [],
            [note(60, 0)],
            [note(21 + i, i * PPQ // 8, PPQ // 8, "A0") for i in range(32)],
        ]
        for notes in cases:
            result = analyse_measure(measure(), notes, all_right(len(notes)), PPQ)
            assert 0.0 <= result.score <= 10.0

    def test_empty_measure_scores_zero(self):
        assert analyse_measure(measure(), [], {}, PPQ).score == 0.0

    def test_all_features_normalised(self):
        notes = [note(40 + i * 3, i * PPQ // 4, PPQ // 4) for i in range(12)]
        features = analyse_measure(measure(), notes, all_right(12), PPQ).features

        for name, value in features.as_dict().items():
            assert 0.0 <= value <= 1.0, f"{name} escaped 0..1: {value}"

    def test_compound_metre_beat_length_is_respected(self):
        """In 6/8 the beat is an eighth, not a quarter. Density must not be inflated."""
        notes = [note(60, i * PPQ // 2, PPQ // 2) for i in range(6)]

        simple = analyse_measure(measure(numerator=6, denominator=8), notes, all_right(6), PPQ)
        assert 0.0 <= simple.features.note_density <= 1.0


class TestPatternDetection:
    def test_scale_run_detected(self):
        notes = [note(60 + i, i * PPQ // 4, PPQ // 4) for i in range(8)]
        result = analyse_measure(measure(), notes, all_right(8), PPQ)

        assert TechnicalPattern.SCALE_RUN in result.patterns

    def test_octave_leap_detected(self):
        notes = [note(48, 0), note(72, PPQ), note(48, PPQ * 2), note(72, PPQ * 3)]
        result = analyse_measure(measure(), notes, all_right(4), PPQ)

        assert TechnicalPattern.OCTAVE_LEAP in result.patterns

    def test_cross_hand_detected(self):
        notes = [note(60, 0), note(72, 0)]
        hands = {0: Hand.RIGHT, 1: Hand.LEFT}
        result = analyse_measure(measure(), notes, hands, PPQ)

        assert TechnicalPattern.CROSS_HAND in result.patterns


class TestChunking:
    @staticmethod
    def _uniform_score(measures, score: float):
        from pilearn_worker.pedagogy.difficulty import MeasureAnalysis, MeasureFeatures

        return {
            m.index: MeasureAnalysis(m.index, MeasureFeatures(), score, ())
            for m in measures
        }

    def test_chunks_cover_every_measure_exactly_once(self):
        measures = [measure(i) for i in range(16)]
        chunks = build_chunks(measures, self._uniform_score(measures, 4.0))

        covered = []
        for chunk in chunks:
            covered.extend(range(chunk.start_measure, chunk.end_measure + 1))

        assert sorted(covered) == list(range(16))
        assert len(covered) == len(set(covered)), "a measure appears in two chunks"

    def test_chunk_sizes_respect_bounds(self):
        measures = [measure(i) for i in range(24)]
        chunks = build_chunks(measures, self._uniform_score(measures, 3.0))

        for chunk in chunks:
            if chunk.kind == "PRIMARY":
                assert chunk.measure_count <= MAX_CHUNK_MEASURES

    def test_harder_music_produces_smaller_chunks(self):
        measures = [measure(i) for i in range(24)]

        easy = build_chunks(measures, self._uniform_score(measures, 1.0))
        hard = build_chunks(measures, self._uniform_score(measures, 8.0))

        easy_mean = sum(c.measure_count for c in easy) / len(easy)
        hard_mean = sum(c.measure_count for c in hard) / len(hard)

        assert hard_mean < easy_mean

    def test_hard_measure_is_isolated_into_its_own_chunk(self):
        from pilearn_worker.pedagogy.difficulty import MeasureAnalysis, MeasureFeatures

        measures = [measure(i) for i in range(12)]
        analyses = {
            m.index: MeasureAnalysis(
                m.index, MeasureFeatures(), 9.5 if m.index == 5 else 2.0, ()
            )
            for m in measures
        }
        chunks = build_chunks(measures, analyses)

        micro = [c for c in chunks if c.kind == "MICRO"]
        assert micro, "a bar 4.75x the local mean must be isolated"
        assert any(c.start_measure == 5 and c.end_measure == 5 for c in micro)

    def test_chunk_ends_at_a_phrase_boundary_when_one_is_near(self):
        """A rest covering half the bar ends a phrase. The chunk must end there rather
        than cutting mid-phrase to hit a round number."""
        measures = [measure(i) for i in range(12)]
        rests = {
            5: [RawRest(start_tick=0, duration_ticks=PPQ * 3, measure_index=5, staff=1, voice=1)]
        }
        chunks = build_chunks(measures, self._uniform_score(measures, 3.0), rests)

        ends = {c.end_measure for c in chunks}
        assert 5 in ends
        assert any(c.boundary_reason == "REST" for c in chunks)

    def test_double_barline_is_a_boundary(self):
        measures = [measure(i) for i in range(10)]
        measures[3] = measure(3, has_double_barline=True)
        chunks = build_chunks(measures, self._uniform_score(measures, 4.0))

        assert 3 in {c.end_measure for c in chunks}

    def test_chunks_are_contiguous_and_ordered(self):
        measures = [measure(i) for i in range(20)]
        chunks = build_chunks(measures, self._uniform_score(measures, 5.0))

        for previous, current in zip(chunks, chunks[1:]):
            assert current.start_measure == previous.end_measure + 1
            assert current.ordinal == previous.ordinal + 1

    def test_empty_score_produces_no_chunks(self):
        assert build_chunks([], {}) == []

    def test_single_measure_score(self):
        measures = [measure(0)]
        chunks = build_chunks(measures, self._uniform_score(measures, 5.0))

        assert len(chunks) == 1
        assert chunks[0].start_measure == chunks[0].end_measure == 0
