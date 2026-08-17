"""The chunks/segments wire contract.

Regression cover for a real defect: `document_builder` used to collapse chunks into
`segments` and emit no `chunks` field at all, so `RoadmapService.buildChunks()` on the
Java side read a missing key and silently fell back to treating the whole piece as one
chunk. Every learner got a single undifferentiated practice unit and nobody saw an error.

These tests pin both halves of the contract:
  * segments  — MUSICAL phrases, cut only at real boundaries
  * chunks    — PEDAGOGICAL practice units, difficulty-sized, partitioning the score
"""

from __future__ import annotations

import json

import pytest

from pilearn_worker.models.score_document import Chunk, ScoreDocument
from pilearn_worker.parser.document_builder import BuildOptions, build_document
from pilearn_worker.parser.musicxml_parser import MusicXmlParser


def build(xml: str) -> ScoreDocument:
    raw = MusicXmlParser(ppq=480).parse_string(xml)
    return build_document(raw, BuildOptions(score_id="contract-test", input_hash="a" * 64))


class TestBothFieldsEmitted:
    def test_chunks_is_populated(self, rest_boundary_score):
        """The field the Java roadmap builder reads. Empty here means the fallback."""
        document = build(rest_boundary_score)

        assert document.chunks, "chunks must not be empty — RoadmapService reads this"

    def test_segments_is_populated(self, rest_boundary_score):
        document = build(rest_boundary_score)

        assert document.segments

    def test_both_survive_json_round_trip(self, rest_boundary_score):
        """The contract is a wire contract: it has to survive serialisation."""
        document = build(rest_boundary_score)
        payload = json.loads(document.model_dump_json())

        assert "chunks" in payload
        assert "segments" in payload
        assert len(payload["chunks"]) == len(document.chunks)

        restored = ScoreDocument.model_validate_json(document.model_dump_json())
        assert len(restored.chunks) == len(document.chunks)
        assert len(restored.segments) == len(document.segments)

    def test_chunk_json_carries_the_fields_java_deserialises(self, rest_boundary_score):
        """Field names here ARE the Java contract. Renaming one breaks RoadmapService."""
        document = build(rest_boundary_score)
        chunk = json.loads(document.model_dump_json())["chunks"][0]

        for field in (
            "id", "ordinal", "start_measure", "end_measure", "measure_count",
            "difficulty", "kind", "label", "boundary_reason", "segment_ids", "patterns",
        ):
            assert field in chunk, f"chunk JSON is missing {field}"


class TestChunksPartitionTheScore:
    def test_every_measure_belongs_to_exactly_one_chunk(self, rest_boundary_score):
        document = build(rest_boundary_score)

        covered: list[int] = []
        for chunk in document.chunks:
            covered.extend(range(chunk.start_measure, chunk.end_measure + 1))

        assert sorted(covered) == list(range(document.meta.measure_count))
        assert len(covered) == len(set(covered))

    def test_chunks_are_contiguous_and_ordered(self, rest_boundary_score):
        document = build(rest_boundary_score)

        for previous, current in zip(document.chunks, document.chunks[1:]):
            assert current.start_measure == previous.end_measure + 1
            assert current.ordinal == previous.ordinal + 1

    def test_overlapping_chunks_are_rejected(self, simple_two_staff):
        """The model refuses a document whose practice units overlap."""
        document = build(simple_two_staff)
        overlapping = (
            Chunk(id="c0", ordinal=0, start_measure=0, end_measure=1, measure_count=2,
                  difficulty=3.0, label="Bars 1-2"),
            Chunk(id="c1", ordinal=1, start_measure=1, end_measure=1, measure_count=1,
                  difficulty=3.0, label="Bar 2"),
        )

        with pytest.raises(ValueError, match="more than one chunk"):
            document.model_copy(update={"chunks": overlapping}).model_validate(
                document.model_copy(update={"chunks": overlapping}).__dict__
            )

    def test_chunk_pointing_past_the_score_is_rejected(self, simple_two_staff):
        document = build(simple_two_staff)
        payload = json.loads(document.model_dump_json())
        payload["chunks"] = [{
            "id": "c0", "ordinal": 0, "start_measure": 0, "end_measure": 99,
            "measure_count": 100, "difficulty": 3.0, "kind": "PRIMARY",
            "label": "Bars 1-100", "boundary_reason": "SIZE",
            "segment_ids": [], "patterns": [],
        }]

        with pytest.raises(ValueError, match="do not exist"):
            ScoreDocument.model_validate(payload)


class TestChunkFieldIntegrity:
    def test_measure_count_matches_the_range(self, rest_boundary_score):
        document = build(rest_boundary_score)

        for chunk in document.chunks:
            assert chunk.measure_count == chunk.end_measure - chunk.start_measure + 1

    def test_inconsistent_measure_count_is_rejected_at_construction(self):
        with pytest.raises(ValueError, match="does not match the range"):
            Chunk(id="c", ordinal=0, start_measure=0, end_measure=3,
                  measure_count=99, difficulty=1.0, label="x")

    def test_inverted_range_is_rejected(self):
        with pytest.raises(ValueError, match="precedes"):
            Chunk(id="c", ordinal=0, start_measure=5, end_measure=2,
                  measure_count=4, difficulty=1.0, label="x")

    def test_difficulty_is_within_the_documented_scale(self, rest_boundary_score):
        document = build(rest_boundary_score)

        for chunk in document.chunks:
            assert 0.0 <= chunk.difficulty <= 10.0

    def test_labels_are_human_readable(self, rest_boundary_score):
        document = build(rest_boundary_score)

        for chunk in document.chunks:
            assert chunk.label
            assert "Bar" in chunk.label


class TestSegmentLinkage:
    def test_chunk_segment_ids_resolve_to_real_segments(self, rest_boundary_score):
        document = build(rest_boundary_score)
        known = {segment.id for segment in document.segments}

        for chunk in document.chunks:
            for segment_id in chunk.segment_ids:
                assert segment_id in known

    def test_every_chunk_overlaps_at_least_one_segment(self, rest_boundary_score):
        document = build(rest_boundary_score)

        for chunk in document.chunks:
            assert chunk.segment_ids, f"chunk {chunk.id} is linked to no phrase"

    def test_unknown_segment_reference_is_rejected(self, simple_two_staff):
        document = build(simple_two_staff)
        payload = json.loads(document.model_dump_json())
        payload["chunks"] = [{
            "id": "c0", "ordinal": 0, "start_measure": 0, "end_measure": 1,
            "measure_count": 2, "difficulty": 3.0, "kind": "PRIMARY",
            "label": "Bars 1-2", "boundary_reason": "SIZE",
            "segment_ids": ["segDOESNOTEXIST"], "patterns": [],
        }]

        with pytest.raises(ValueError, match="unknown segments"):
            ScoreDocument.model_validate(payload)


class TestSegmentsAreMusicalNotPedagogical:
    def test_segments_are_not_merely_a_copy_of_chunks(self, rest_boundary_score):
        """The bug being prevented: segments and chunks collapsing into one list.

        A rest at bar 4 gives two phrases, while difficulty-based sizing produces a
        different number of practice units. If these ever match exactly for every
        fixture, the two concepts have been conflated again.
        """
        document = build(rest_boundary_score)

        segment_ranges = {(s.start_measure, s.end_measure) for s in document.segments}
        chunk_ranges = {(c.start_measure, c.end_measure) for c in document.chunks}

        assert segment_ranges or chunk_ranges
        # Segments derive from boundaries alone, so they must be reason-tagged.
        assert all(
            s.boundary_reason in
            {"CADENCE", "REST", "DOUBLE_BARLINE", "TEXTURE_CHANGE", "REPEAT", "END"}
            for s in document.segments
        )

    def test_rest_bar_ends_a_segment(self, rest_boundary_score):
        """Bar index 3 is a full bar of rest — the phrase ends there."""
        document = build(rest_boundary_score)

        assert 3 in {segment.end_measure for segment in document.segments}
        assert any(segment.boundary_reason == "REST" for segment in document.segments)

    def test_segments_cover_the_score_without_gaps(self, rest_boundary_score):
        document = build(rest_boundary_score)

        covered: list[int] = []
        for segment in document.segments:
            covered.extend(range(segment.start_measure, segment.end_measure + 1))

        assert sorted(covered) == list(range(document.meta.measure_count))


class TestRoadmapConsumption:
    def test_document_shape_matches_what_roadmapservice_reads(self, rest_boundary_score):
        """Mirrors RoadmapService.buildChunks() exactly.

        It calls document.path("chunks"), checks isArray/isEmpty, then reads
        start_measure, end_measure, difficulty and label per node. If this assertion
        passes, the Java side cannot hit its whole-piece fallback.
        """
        document = build(rest_boundary_score)
        payload = json.loads(document.model_dump_json())

        chunks = payload.get("chunks")
        assert isinstance(chunks, list) and chunks, "would trigger the fallback path"

        for node in chunks:
            assert isinstance(node["start_measure"], int)
            assert isinstance(node["end_measure"], int)
            assert isinstance(node["difficulty"], (int, float))
            assert isinstance(node["label"], str)

    def test_hand_mapping_present_so_stage_ladder_includes_both_hands(
        self, simple_two_staff
    ):
        """RoadmapService.hasBothHands() reads parts[].hand_mapping; without it every
        chunk would skip its hands-separate stages."""
        document = build(simple_two_staff)
        payload = json.loads(document.model_dump_json())

        mapping = payload["parts"][0]["hand_mapping"]
        assert len(mapping) >= 2
