"""End-to-end: MusicXML -> RawScore -> ScoreDocument.

The final assertion in most of these is simply that `ScoreDocument` constructed at all —
its `validate_consistency` validator checks that the hierarchy and the flat alignment
describe the same music, so a successful build is a real structural guarantee, not a
smoke test.
"""

from __future__ import annotations

import pytest

from pilearn_worker.models.score_document import Hand, ReviewStatus, ScoreDocument
from pilearn_worker.parser.document_builder import (
    BuildOptions,
    DocumentBuildError,
    UnresolvedRepeatError,
    build_document,
)
from pilearn_worker.parser.musicxml_parser import (
    GRACE_NOTE_TICKS,
    MusicXmlParseError,
    MusicXmlParser,
)

PPQ = 480


def parse(xml: str):
    return MusicXmlParser(ppq=PPQ).parse_string(xml)


def build(xml: str, **kwargs) -> ScoreDocument:
    options = BuildOptions(score_id=kwargs.pop("score_id", "test-score"),
                           input_hash="a" * 64, **kwargs)
    return build_document(parse(xml), options)


class TestParserStructure:
    def test_two_staves_merge_into_one_logical_part(self, simple_two_staff):
        """music21 splits a grand staff into two PartStaff objects. A piano is one
        instrument, and hand assignment must see it that way."""
        raw = parse(simple_two_staff)

        assert len(raw.parts) == 1
        assert raw.parts[0].staff_count == 2
        assert {n.staff for n in raw.parts[0].notes} == {1, 2}

    def test_metadata_is_extracted(self, simple_two_staff):
        raw = parse(simple_two_staff)

        assert raw.title == "Test Piece"
        assert raw.composer == "Test Composer"
        assert raw.ppq == PPQ

    def test_measures_are_contiguous_in_ticks(self, simple_two_staff):
        raw = parse(simple_two_staff)
        measures = raw.parts[0].measures

        assert measures[0].start_tick == 0
        for previous, current in zip(measures, measures[1:]):
            assert current.start_tick == previous.end_tick

    def test_notes_carry_staff_and_voice(self, simple_two_staff):
        raw = parse(simple_two_staff)
        treble = [n for n in raw.parts[0].notes if n.staff == 1]
        bass = [n for n in raw.parts[0].notes if n.staff == 2]

        assert len(treble) == 6
        assert len(bass) == 3
        assert all(n.midi >= 60 for n in treble)
        assert all(n.midi < 60 for n in bass)

    def test_empty_score_is_rejected_with_a_clear_message(self):
        with pytest.raises(MusicXmlParseError):
            parse("<score-partwise version='4.0'></score-partwise>")


class TestPickup:
    def test_pickup_measure_is_flagged(self, pickup_score):
        raw = parse(pickup_score)

        assert raw.parts[0].measures[0].is_pickup
        assert not raw.parts[0].measures[1].is_pickup

    def test_pickup_note_sits_at_the_end_of_the_bar(self, pickup_score):
        """An upbeat quarter in 4/4 starts on beat 4, not beat 1. Getting this wrong
        shifts every subsequent cursor position by three beats."""
        raw = parse(pickup_score)
        upbeat = raw.parts[0].notes[0]

        assert upbeat.beat_offset == pytest.approx(3.0)
        assert upbeat.start_tick == 3 * PPQ

    def test_document_records_the_pickup(self, pickup_score):
        assert build(pickup_score).meta.has_pickup


class TestChordsGraceAndTies:
    def test_chord_notes_share_an_onset(self, chord_and_grace_score):
        raw = parse(chord_and_grace_score)
        at_zero = [n for n in raw.parts[0].notes if n.start_tick == 0 and n.staff == 1]

        assert len(at_zero) == 3
        assert {n.midi for n in at_zero} == {72, 76, 79}
        assert all(n.is_chord_member for n in at_zero)

    def test_chord_members_share_a_chord_id(self, chord_and_grace_score):
        document = build(chord_and_grace_score)
        first_measure = document.measure(0)
        chord_notes = [n for n in first_measure.notes if n.start_tick == 0 and n.staff == 1]

        chord_ids = {n.chord_id for n in chord_notes}
        assert len(chord_ids) == 1
        assert chord_ids.pop() is not None

    def test_hands_striking_together_are_not_fused_into_one_chord(self, simple_two_staff):
        """Both hands play at tick 0. They must not share a chord_id — a chord is one
        hand's simultaneity, not the score's."""
        document = build(simple_two_staff)
        at_zero = [n for n in document.measure(0).notes if n.start_tick == 0]
        right = [n for n in at_zero if n.hand is Hand.RIGHT]
        left = [n for n in at_zero if n.hand is Hand.LEFT]

        assert right and left
        assert not ({n.chord_id for n in right} & {n.chord_id for n in left} - {None})

    def test_grace_note_gets_a_positive_sounding_duration(self, chord_and_grace_score):
        """Grace notes have quarterLength 0. ScoreNote requires a positive duration and
        playback needs something to sound."""
        raw = parse(chord_and_grace_score)
        graces = [n for n in raw.parts[0].notes if n.is_grace]

        assert len(graces) == 1
        assert graces[0].duration_ticks == GRACE_NOTE_TICKS
        assert graces[0].midi == 78  # F#5

    def test_tie_start_is_recorded(self, chord_and_grace_score):
        raw = parse(chord_and_grace_score)

        assert any(n.tie_start for n in raw.parts[0].notes)

    def test_spelling_is_preserved_separately_from_pitch(self, chord_and_grace_score):
        """C#4 and Db4 share a MIDI number but are different notes on the page."""
        raw = parse(chord_and_grace_score)
        grace = next(n for n in raw.parts[0].notes if n.is_grace)

        assert grace.spelled.startswith("F#")


class TestMultiVoice:
    def test_voices_are_kept_separate(self, multi_voice_score):
        raw = parse(multi_voice_score)
        treble_voices = {n.voice for n in raw.parts[0].notes if n.staff == 1}

        assert len(treble_voices) >= 2

    def test_document_groups_notes_into_voice_objects(self, multi_voice_score):
        document = build(multi_voice_score)
        measure = document.measure(0)

        treble_voices = [v for v in measure.voices if v.staff == 1]
        assert len(treble_voices) >= 2
        assert all(v.note_count > 0 for v in measure.voices)

    def test_every_note_appears_in_exactly_one_voice(self, multi_voice_score):
        document = build(multi_voice_score)
        measure = document.measure(0)

        ids = [n.id for v in measure.voices for n in v.notes]
        assert len(ids) == len(set(ids))


class TestCompoundMetre:
    def test_beat_offsets_use_the_eighth_as_the_beat(self, compound_metre_score):
        """In 6/8, six eighths are beats 0..5. Treating the quarter as the beat would
        report the last note as beat 2.5 and mislabel every practice instruction."""
        raw = parse(compound_metre_score)
        offsets = [n.beat_offset for n in raw.parts[0].notes]

        assert offsets == pytest.approx([0.0, 1.0, 2.0, 3.0, 4.0, 5.0])

    def test_document_builds_in_compound_metre(self, compound_metre_score):
        document = build(compound_metre_score)

        assert document.measure(0).time_signature.numerator == 6
        assert document.measure(0).time_signature.denominator == 8


class TestOutOfRange:
    def test_pitch_outside_the_keyboard_is_dropped_with_a_warning(self, out_of_range_score):
        """C-1 is below A0. Dropping it silently would corrupt the score; crashing would
        reject an otherwise usable one."""
        raw = parse(out_of_range_score)

        assert raw.note_count == 1
        assert any("outside the 88-key range" in w for w in raw.warnings)

    def test_warnings_reach_the_confidence_report(self, out_of_range_score):
        document = build(out_of_range_score)

        assert document.confidence.issues
        assert document.confidence.document_confidence < 1.0


class TestAlignmentIndex:
    def test_one_step_per_distinct_onset(self, simple_two_staff):
        document = build(simple_two_staff)
        onsets = {n.start_tick for m in document.measures for n in m.notes}

        assert len(document.alignment.steps) == len(onsets)

    def test_cursor_indices_are_dense_and_ordered(self, simple_two_staff):
        """OSMD's cursor advances one position per step. A gap desynchronises it."""
        document = build(simple_two_staff)
        indices = [s.osmd_cursor_index for s in document.alignment.steps]

        assert indices == list(range(len(indices)))

    def test_steps_are_time_ordered(self, simple_two_staff):
        document = build(simple_two_staff)
        times = [s.start_sec for s in document.alignment.steps]

        assert times == sorted(times)

    def test_every_step_note_id_resolves_to_a_real_note(self, simple_two_staff):
        document = build(simple_two_staff)
        known = {n.id for m in document.measures for n in m.notes}

        for step in document.alignment.steps:
            for note_id in step.note_ids:
                assert note_id in known

    def test_parallel_arrays_stay_aligned(self, simple_two_staff):
        document = build(simple_two_staff)

        for step in document.alignment.steps:
            assert len(step.note_ids) == len(step.pitches) == len(step.hands)

    def test_by_tick_lookup_is_o1_and_correct(self, simple_two_staff):
        """This is what replaces the legacy multi-pass search in cursor.service.ts."""
        document = build(simple_two_staff)
        index = document.alignment

        for step in index.steps:
            resolved = index.steps[index.by_tick[step.start_tick]]
            assert resolved.start_tick == step.start_tick

    def test_by_measure_points_at_each_measure_first_step(self, simple_two_staff):
        document = build(simple_two_staff)
        index = document.alignment

        for measure_index, step_index in index.by_measure.items():
            assert index.steps[step_index].measure_index == measure_index

    def test_seconds_match_the_tempo(self, simple_two_staff):
        """At 120 bpm a quarter is 0.5 s. If this drifts, so does the cursor."""
        document = build(simple_two_staff)
        steps = document.alignment.steps

        assert steps[0].start_sec == pytest.approx(0.0)
        assert steps[1].start_sec == pytest.approx(0.5, abs=0.01)


class TestRepeatUnrolling:
    def test_simple_repeat_expands(self, repeat_score):
        """Bars 1-2 repeated then bar 3: performance order is 0,1,0,1,2."""
        document = build(repeat_score)

        assert document.playback_order == (0, 1, 0, 1, 2)

    def test_repeated_measures_produce_repeated_steps(self, repeat_score):
        document = build(repeat_score)
        measure_sequence = [s.measure_index for s in document.alignment.steps]

        assert measure_sequence.count(0) == 2
        assert measure_sequence.count(2) == 1

    def test_no_repeats_yields_linear_order(self, simple_two_staff):
        document = build(simple_two_staff)

        assert document.playback_order == (0, 1)

    def test_cyclic_structure_raises_instead_of_truncating(self):
        """The legacy engine capped its loop at `security < 10000` and returned a
        silently truncated score. An unresolvable structure must be reported."""
        from pilearn_worker.models.raw import RawMeasure
        from pilearn_worker.parser.document_builder import ScoreDocumentBuilder

        builder = ScoreDocumentBuilder(BuildOptions(score_id="x"))
        # Every bar jumps back to the top: this never terminates.
        measures = tuple(
            RawMeasure(
                index=i, number=str(i + 1), start_tick=i * 1920,
                end_tick=(i + 1) * 1920, time_numerator=4, time_denominator=4,
                key_fifths=0, jump="DC",
            )
            for i in range(3)
        )

        with pytest.raises(UnresolvedRepeatError):
            builder._resolve_playback_order(measures)


class TestChunking:
    def test_rest_bar_becomes_a_segment_boundary(self, rest_boundary_score):
        document = build(rest_boundary_score)

        assert document.segments
        boundaries = {s.end_measure for s in document.segments}
        assert 3 in boundaries or any(s.boundary_reason == "REST" for s in document.segments)

    def test_segments_cover_every_measure(self, rest_boundary_score):
        document = build(rest_boundary_score)

        covered: list[int] = []
        for segment in document.segments:
            covered.extend(range(segment.start_measure, segment.end_measure + 1))

        assert sorted(covered) == list(range(document.meta.measure_count))


class TestDocumentInvariants:
    def test_document_validates_end_to_end(self, simple_two_staff):
        document = build(simple_two_staff)

        assert document.schema_version == "1.0"
        assert document.meta.measure_count == 2
        assert document.difficulty is not None
        assert document.confidence.status is ReviewStatus.OK

    def test_symbolic_source_has_no_dropped_pages(self, simple_two_staff):
        """MusicXML that parsed cleanly has no recognition step to be uncertain about."""
        document = build(simple_two_staff)

        assert document.confidence.dropped_pages == ()

    def test_hand_mapping_is_populated_for_a_grand_staff(self, simple_two_staff):
        document = build(simple_two_staff)
        mapping = document.parts[0].hand_mapping

        assert mapping[1] is Hand.RIGHT
        assert mapping[2] is Hand.LEFT

    def test_difficulty_is_computed_per_measure(self, simple_two_staff):
        document = build(simple_two_staff)

        for measure in document.measures:
            assert measure.difficulty is not None
            assert 0.0 <= measure.difficulty.score <= 10.0

    def test_notes_in_range_helper_filters_by_hand(self, simple_two_staff):
        document = build(simple_two_staff)

        right = list(document.notes_in_range(0, 1, Hand.RIGHT))
        left = list(document.notes_in_range(0, 1, Hand.LEFT))

        assert right and left
        assert all(n.hand is Hand.RIGHT for n in right)
        assert all(n.hand is Hand.LEFT for n in left)

    def test_document_serialises_to_json_and_back(self, simple_two_staff):
        document = build(simple_two_staff)
        payload = document.model_dump_json()
        restored = ScoreDocument.model_validate_json(payload)

        assert restored.meta.measure_count == document.meta.measure_count
        assert len(restored.alignment.steps) == len(document.alignment.steps)

    def test_empty_note_set_is_rejected(self):
        from pilearn_worker.models.raw import RawMeasure, RawPart, RawScore

        raw = RawScore(
            title="Empty", composer="X", divisions=PPQ, ppq=PPQ,
            parts=(RawPart(id="P1", name=None, staff_count=1,
                           measures=(RawMeasure(index=0, number="1", start_tick=0,
                                                end_tick=1920, time_numerator=4,
                                                time_denominator=4, key_fifths=0),),
                           notes=()),),
        )

        with pytest.raises(DocumentBuildError, match="no notes"):
            build_document(raw, BuildOptions(score_id="empty"))
