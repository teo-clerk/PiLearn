"""RawScore -> ScoreDocument.

Pure: no music21, no I/O. Given a RawScore it produces a fully validated ScoreDocument,
running hand assignment and difficulty analysis on the way.

This is where the client-side work that `cursor.service.ts` did per page load — 1,101
lines of OSMD/MIDI alignment with magic iteration guards — becomes a precomputed index.

Repeat unrolling
----------------
The legacy implementation guarded its loops with `security < 10000` and `MAX_DACAPO`, so
an unresolvable repeat structure degraded into a silently truncated score. Here an
unresolvable structure raises `UnresolvedRepeatError`. A reported failure the user can
act on beats a wrong cursor they cannot diagnose.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from pilearn_worker.models.raw import RawMeasure, RawNote, RawPart, RawScore
from pilearn_worker.models.score_document import (
    AlignmentIndex,
    Chunk as DocumentChunk,
    ConfidenceReport,
    DifficultySummary,
    Hand,
    HarmonyEntry,
    IssueCode,
    KeySignature,
    Measure,
    MeasureDifficulty,
    Mode,
    OmrEngine,
    Part,
    ReviewStatus,
    ScoreDocument,
    ScoreMeta,
    ScoreNote,
    Segment,
    SourceInfo,
    SourceKind,
    TechnicalPattern,
    TimelineStep,
    TimeSignature,
    Voice,
)
from pilearn_worker.pedagogy import difficulty as difficulty_module
from pilearn_worker.pedagogy.hand_detector import Hand as PedagogyHand
from pilearn_worker.pedagogy.hand_detector import assign_hands

logger = logging.getLogger(__name__)

# Hard ceiling on unrolled length. A legitimate score does not expand past this; hitting
# it means the repeat structure is cyclic, which we report rather than truncate.
MAX_UNROLLED_MEASURES = 10_000
MAX_DA_CAPO_JUMPS = 4


class DocumentBuildError(RuntimeError):
    """The RawScore could not be turned into a valid ScoreDocument."""


class UnresolvedRepeatError(DocumentBuildError):
    """The repeat structure could not be resolved into a linear performance order."""


@dataclass(frozen=True, slots=True)
class BuildOptions:
    score_id: str
    revision: int = 1
    analysis_version: str = "analysis-2026.08"
    pipeline_version: str = "legacy-shell-1"
    source_kind: SourceKind = SourceKind.MUSICXML
    input_hash: str = ""
    page_count: int | None = None
    omr_engine: OmrEngine = OmrEngine.NONE
    omr_engine_version: str | None = None
    target_tempo_bpm: float | None = None


def _to_document_hand(hand: PedagogyHand) -> Hand:
    return Hand.RIGHT if hand is PedagogyHand.RIGHT else Hand.LEFT


def _ticks_to_seconds(ticks: int, ppq: int, bpm: float) -> float:
    """Convert ticks to seconds at a constant tempo.

    Tempo changes are applied by the caller walking the tempo map measure by measure;
    this handles one segment.
    """
    if ppq <= 0 or bpm <= 0:
        return 0.0
    return (ticks / ppq) * (60.0 / bpm)


class ScoreDocumentBuilder:
    """Builds the canonical document from a parsed score."""

    def __init__(self, options: BuildOptions) -> None:
        self.options = options

    def build(self, raw: RawScore) -> ScoreDocument:
        if not raw.parts:
            raise DocumentBuildError("RawScore contains no parts")
        if raw.note_count == 0:
            raise DocumentBuildError("RawScore contains no notes")

        # Solo piano is one logical part. Multi-part input is accepted, but hand
        # assignment and difficulty run per part.
        parts: list[Part] = []
        all_measure_analyses: dict[int, difficulty_module.MeasureAnalysis] = {}

        for raw_part in raw.parts:
            part, analyses = self._build_part(raw_part, raw)
            parts.append(part)
            all_measure_analyses.update(analyses)

        primary = raw.parts[0]
        playback_order = self._resolve_playback_order(primary.measures)

        timing = self._build_timing(primary.measures, raw.ppq)
        alignment = self._build_alignment(
            parts[0], primary, playback_order, raw.ppq, timing
        )

        segments, chunks = self._build_segments_and_chunks(primary, all_measure_analyses)
        meta = self._build_meta(raw, primary, timing)
        summary = self._build_difficulty_summary(all_measure_analyses)
        confidence = self._build_confidence(raw)

        document = ScoreDocument(
            score_id=self.options.score_id,
            revision=self.options.revision,
            analysis_version=self.options.analysis_version,
            source=SourceInfo(
                kind=self.options.source_kind,
                input_hash=self.options.input_hash or ("0" * 64),
                page_count=self.options.page_count,
                omr_engine=self.options.omr_engine,
                omr_engine_version=self.options.omr_engine_version,
                pipeline_version=self.options.pipeline_version,
            ),
            meta=meta,
            parts=tuple(parts),
            playback_order=playback_order,
            alignment=alignment,
            segments=segments,
            chunks=chunks,
            harmony=tuple(
                HarmonyEntry(
                    measure=h.measure_index,
                    beat=h.beat,
                    root=h.root,
                    kind=h.kind,
                    bass=h.bass,
                    roman=h.roman,
                )
                for h in raw.harmony
            ),
            difficulty=summary,
            confidence=confidence,
        )

        logger.info(
            "built ScoreDocument %s rev %d: %d measures, %d steps, grade %.1f",
            document.score_id, document.revision, meta.measure_count,
            len(alignment.steps), summary.global_grade if summary else 0.0,
        )
        return document

    # ── Timing ──────────────────────────────────────────────────────────────

    def _build_timing(
        self, measures: tuple[RawMeasure, ...], ppq: int
    ) -> dict[int, tuple[float, float]]:
        """Absolute start/end seconds per measure index, honouring tempo changes."""
        timing: dict[int, tuple[float, float]] = {}
        elapsed = 0.0
        for measure in measures:
            duration = _ticks_to_seconds(
                measure.end_tick - measure.start_tick, ppq, measure.tempo_bpm
            )
            timing[measure.index] = (elapsed, elapsed + duration)
            elapsed += duration
        return timing

    def _note_seconds(
        self, note: RawNote, measure: RawMeasure, ppq: int,
        timing: dict[int, tuple[float, float]],
    ) -> tuple[float, float]:
        measure_start_sec = timing.get(measure.index, (0.0, 0.0))[0]
        offset_sec = _ticks_to_seconds(
            note.start_tick - measure.start_tick, ppq, measure.tempo_bpm
        )
        duration_sec = _ticks_to_seconds(note.duration_ticks, ppq, measure.tempo_bpm)
        return measure_start_sec + offset_sec, max(duration_sec, 0.001)

    # ── Parts ───────────────────────────────────────────────────────────────

    def _build_part(
        self, raw_part: RawPart, raw: RawScore
    ) -> tuple[Part, dict[int, difficulty_module.MeasureAnalysis]]:
        notes = raw_part.notes

        staff_map = self._infer_staff_map(raw_part)
        assignment = assign_hands(notes, staff_map)
        hands_by_index = assignment.hands

        logger.debug(
            "part %s: hands assigned by %s (confidence %.2f, %d crossings)",
            raw_part.id, assignment.method.value, assignment.confidence,
            assignment.metrics.crossings,
        )

        timing = self._build_timing(raw_part.measures, raw.ppq)
        measures_by_index = {m.index: m for m in raw_part.measures}

        notes_by_measure: dict[int, list[tuple[int, RawNote]]] = {}
        for index, note in enumerate(notes):
            notes_by_measure.setdefault(note.measure_index, []).append((index, note))

        rests_by_measure: dict[int, list] = {}
        for rest in raw_part.rests:
            rests_by_measure.setdefault(rest.measure_index, []).append(rest)

        built_measures: list[Measure] = []
        analyses: dict[int, difficulty_module.MeasureAnalysis] = {}

        for raw_measure in raw_part.measures:
            entries = notes_by_measure.get(raw_measure.index, [])
            measure_notes = [note for _, note in entries]
            local_hands = {
                position: hands_by_index.get(global_index, PedagogyHand.RIGHT)
                for position, (global_index, _) in enumerate(entries)
            }

            analysis = difficulty_module.analyse_measure(
                raw_measure, measure_notes, local_hands, raw.ppq
            )
            analyses[raw_measure.index] = analysis

            built_measures.append(
                self._build_measure(
                    raw_measure, entries, hands_by_index, raw.ppq, timing, analysis
                )
            )

        hand_mapping = {
            staff: _to_document_hand(hand) for staff, hand in (staff_map or {}).items()
        }
        if not hand_mapping:
            hand_mapping = {1: Hand.RIGHT} if raw_part.staff_count == 1 else {
                1: Hand.RIGHT, 2: Hand.LEFT
            }

        part = Part(
            id=raw_part.id,
            name=raw_part.name,
            staff_count=max(1, raw_part.staff_count),
            hand_mapping=hand_mapping,
            measures=tuple(built_measures),
        )
        return part, analyses

    def _infer_staff_map(self, raw_part: RawPart) -> dict[PedagogyHand, PedagogyHand]:
        """Map staff numbers to hands.

        Grand staff convention: staff 1 is the right hand, staff 2 the left. Only
        asserted when the part actually has two staves — a single-staff source carries no
        hand information and must go through the split heuristic instead.
        """
        staves = sorted({note.staff for note in raw_part.notes})
        if len(staves) < 2:
            return {}
        mapping = {staves[0]: PedagogyHand.RIGHT, staves[1]: PedagogyHand.LEFT}
        for extra in staves[2:]:
            mapping[extra] = PedagogyHand.LEFT
        return mapping

    def _build_measure(
        self,
        raw_measure: RawMeasure,
        entries: list[tuple[int, RawNote]],
        hands_by_index: dict[int, PedagogyHand],
        ppq: int,
        timing: dict[int, tuple[float, float]],
        analysis: difficulty_module.MeasureAnalysis,
    ) -> Measure:
        start_sec, end_sec = timing.get(raw_measure.index, (0.0, 0.001))
        if end_sec <= start_sec:
            end_sec = start_sec + 0.001

        # Group by (staff, voice): a Voice is one line on one staff.
        grouped: dict[tuple[int, int], list[ScoreNote]] = {}
        voice_hands: dict[tuple[int, int], Hand] = {}

        # Chord grouping is by (onset, staff, voice) so two hands striking simultaneously
        # are not fused into one chord.
        chord_keys: dict[tuple[int, int, int], list[int]] = {}
        for global_index, note in entries:
            chord_keys.setdefault(
                (note.start_tick, note.staff, note.voice), []
            ).append(global_index)

        for global_index, note in entries:
            hand = _to_document_hand(hands_by_index.get(global_index, PedagogyHand.RIGHT))
            note_start_sec, note_duration_sec = self._note_seconds(
                note, raw_measure, ppq, timing
            )

            key = (note.start_tick, note.staff, note.voice)
            chord_id = (
                f"c{abs(hash(key)) % 0xFFFFFFFF:08x}" if len(chord_keys[key]) > 1 else None
            )

            score_note = ScoreNote(
                id=ScoreNote.make_id(
                    raw_measure.index, note.voice, note.start_tick, note.midi
                ),
                midi=note.midi,
                spelled=note.spelled,
                start_tick=note.start_tick,
                duration_ticks=max(1, note.duration_ticks),
                start_sec=round(note_start_sec, 6),
                duration_sec=round(note_duration_sec, 6),
                beat_offset=note.beat_offset,
                hand=hand,
                staff=note.staff,
                voice=note.voice,
                finger=note.finger,
                finger_source="score" if note.finger_from_source
                else ("generated" if note.finger else None),
                # Chord membership is expressed by chord_id being set, not by a
                # separate flag — one source of truth for "these sound together".
                chord_id=chord_id,
                is_grace=note.is_grace,
                is_ornament=note.is_ornament,
                articulations=note.articulations,
                dynamic=note.dynamic,
            )

            voice_key = (note.staff, note.voice)
            grouped.setdefault(voice_key, []).append(score_note)
            voice_hands.setdefault(voice_key, hand)

        voices = tuple(
            Voice(
                number=voice_number,
                staff=staff,
                hand=voice_hands[(staff, voice_number)],
                notes=tuple(sorted(notes, key=lambda n: (n.start_tick, n.midi))),
            )
            for (staff, voice_number), notes in sorted(grouped.items())
        )

        return Measure(
            index=raw_measure.index,
            number=raw_measure.number,
            start_tick=raw_measure.start_tick,
            end_tick=max(raw_measure.end_tick, raw_measure.start_tick + 1),
            start_sec=round(start_sec, 6),
            end_sec=round(end_sec, 6),
            time_signature=TimeSignature(
                numerator=raw_measure.time_numerator,
                denominator=raw_measure.time_denominator,
            ),
            key_signature=KeySignature(
                fifths=max(-7, min(7, raw_measure.key_fifths)),
                mode=Mode.MINOR if raw_measure.key_mode == "minor" else Mode.MAJOR,
            ),
            tempo_bpm=raw_measure.tempo_bpm,
            is_pickup=raw_measure.is_pickup,
            voices=voices,
            difficulty=MeasureDifficulty(
                **analysis.features.as_dict(),
                score=analysis.score,
                patterns=tuple(
                    TechnicalPattern(p.value) for p in analysis.patterns
                ),
                weights_version=analysis.weights_version,
            ),
        )

    # ── Repeat unrolling ────────────────────────────────────────────────────

    def _resolve_playback_order(self, measures: tuple[RawMeasure, ...]) -> tuple[int, ...]:
        """Expand repeats, voltas and D.C./D.S. into a linear measure sequence.

        Unlike the legacy implementation, an unresolvable structure raises rather than
        silently truncating at a magic iteration cap.
        """
        if not measures:
            return ()

        by_index = {m.index: m for m in measures}
        ordered = sorted(by_index)
        order: list[int] = []

        position = 0
        repeat_start = ordered[0]
        taken_repeats: dict[int, int] = {}
        da_capo_jumps = 0
        segno_index = next((m.index for m in measures if m.is_segno), ordered[0])

        while position < len(ordered):
            index = ordered[position]
            measure = by_index[index]

            if measure.starts_repeat:
                repeat_start = index

            # Skip an alternate ending we have already passed on this repetition.
            if measure.volta is not None:
                passes = taken_repeats.get(repeat_start, 0)
                if measure.volta <= passes:
                    position += 1
                    continue

            order.append(index)

            if len(order) > MAX_UNROLLED_MEASURES:
                raise UnresolvedRepeatError(
                    f"repeat structure expanded past {MAX_UNROLLED_MEASURES} measures; "
                    "it is almost certainly cyclic. Fix the repeat marks or remove them."
                )

            if measure.ends_repeat:
                passes = taken_repeats.get(repeat_start, 0) + 1
                taken_repeats[repeat_start] = passes
                if passes < max(1, measure.repeat_times):
                    position = ordered.index(repeat_start)
                    continue

            if measure.jump in ("DC", "DS"):
                da_capo_jumps += 1
                if da_capo_jumps > MAX_DA_CAPO_JUMPS:
                    raise UnresolvedRepeatError(
                        f"more than {MAX_DA_CAPO_JUMPS} D.C./D.S. jumps were taken; "
                        "the structure does not terminate"
                    )
                target = ordered[0] if measure.jump == "DC" else segno_index
                position = ordered.index(target)
                continue

            if measure.jump == "FINE" and da_capo_jumps > 0:
                break

            position += 1

        if not order:
            raise UnresolvedRepeatError("repeat resolution produced an empty performance order")

        return tuple(order)

    # ── Alignment ───────────────────────────────────────────────────────────

    def _build_alignment(
        self,
        part: Part,
        raw_part: RawPart,
        playback_order: tuple[int, ...],
        ppq: int,
        timing: dict[int, tuple[float, float]],
    ) -> AlignmentIndex:
        """Build the flat, performance-ordered step index.

        One step per distinct onset within a measure. That matches how OSMD's cursor
        advances, which is what makes `osmd_cursor_index` a plain running counter rather
        than something the client has to search for.
        """
        measures_by_index = {m.index: m for m in part.measures}
        steps: list[TimelineStep] = []
        by_tick: dict[int, int] = {}
        by_measure: dict[int, int] = {}

        cursor_index = 0
        elapsed_sec = 0.0

        for measure_index in playback_order:
            measure = measures_by_index.get(measure_index)
            if measure is None:
                continue

            if measure_index not in by_measure:
                by_measure[measure_index] = len(steps)

            onsets: dict[int, list[ScoreNote]] = {}
            for voice in measure.voices:
                for note in voice.notes:
                    onsets.setdefault(note.start_tick, []).append(note)

            measure_duration_sec = measure.end_sec - measure.start_sec

            for onset_tick in sorted(onsets):
                notes = sorted(onsets[onset_tick], key=lambda n: n.midi)
                offset_sec = _ticks_to_seconds(
                    onset_tick - measure.start_tick, ppq, measure.tempo_bpm
                )
                duration_ticks = max(
                    1, min(n.duration_ticks for n in notes)
                )

                step = TimelineStep(
                    index=len(steps),
                    measure_index=measure_index,
                    osmd_cursor_index=cursor_index,
                    start_tick=onset_tick,
                    duration_ticks=duration_ticks,
                    start_sec=round(elapsed_sec + offset_sec, 6),
                    note_ids=tuple(n.id for n in notes),
                    pitches=tuple(n.midi for n in notes),
                    hands=tuple(n.hand for n in notes),
                )
                steps.append(step)

                # First writer wins: on a repeat the same tick recurs, and the earliest
                # step is the one a fresh playback should resolve to.
                by_tick.setdefault(onset_tick, step.index)
                cursor_index += 1

            elapsed_sec += measure_duration_sec

        if not steps:
            raise DocumentBuildError("alignment produced no steps; the score has no notes")

        return AlignmentIndex(
            ppq=ppq,
            steps=tuple(steps),
            by_tick=by_tick,
            by_measure=by_measure,
            mean_confidence=1.0,
        )

    # ── Segments, meta, summary ─────────────────────────────────────────────

    _SEGMENT_REASONS = frozenset(
        {"CADENCE", "REST", "DOUBLE_BARLINE", "TEXTURE_CHANGE", "REPEAT", "END"}
    )

    def _build_segments_and_chunks(
        self,
        raw_part: RawPart,
        analyses: dict[int, difficulty_module.MeasureAnalysis],
    ) -> tuple[tuple[Segment, ...], tuple[DocumentChunk, ...]]:
        """Emit BOTH representations, and link them.

        Segments are musical (where the music breathes); chunks are pedagogical (what a
        learner practises in one sitting). They are derived from the same analysis but
        are not 1:1 — a long phrase splits into several chunks, and a hard bar becomes a
        chunk of its own inside a phrase.

        The previous implementation collapsed chunks into segments and emitted no
        `chunks` field at all, so `RoadmapService.buildChunks()` read a missing key and
        silently fell back to treating the whole piece as one chunk.
        """
        rests_by_measure: dict[int, list] = {}
        for rest in raw_part.rests:
            rests_by_measure.setdefault(rest.measure_index, []).append(rest)

        measures = list(raw_part.measures)

        # Musical phrases, from boundary analysis alone — no practice sizing applied.
        segments = self._derive_segments(measures, rests_by_measure)

        # Practice units, difficulty-sized and phrase-aware.
        pedagogy_chunks = difficulty_module.build_chunks(
            measures, analyses, rests_by_measure
        )

        chunks = tuple(
            DocumentChunk(
                id=f"chunk{chunk.ordinal:03d}",
                ordinal=chunk.ordinal,
                start_measure=chunk.start_measure,
                end_measure=chunk.end_measure,
                measure_count=chunk.measure_count,
                difficulty=round(min(10.0, max(0.0, chunk.difficulty)), 3),
                kind="MICRO" if chunk.kind == "MICRO" else "PRIMARY",
                label=chunk.label,
                boundary_reason=chunk.boundary_reason,
                segment_ids=tuple(
                    segment.id
                    for segment in segments
                    if segment.start_measure <= chunk.end_measure
                    and segment.end_measure >= chunk.start_measure
                ),
                patterns=self._patterns_in_range(
                    analyses, chunk.start_measure, chunk.end_measure
                ),
            )
            for chunk in pedagogy_chunks
        )

        return segments, chunks

    def _derive_segments(
        self, measures: list[RawMeasure], rests_by_measure: dict[int, list]
    ) -> tuple[Segment, ...]:
        """Musical phrases: cut only at real boundaries, with no size target."""
        boundaries = difficulty_module._phrase_boundaries(  # noqa: SLF001 - same package
            measures, rests_by_measure, ppq=1
        )

        segments: list[Segment] = []
        start = 0
        for position, measure in enumerate(measures):
            reason = boundaries.get(measure.index)
            is_last = position == len(measures) - 1
            if reason is None and not is_last:
                continue

            segments.append(
                Segment(
                    id=f"seg{len(segments):03d}",
                    start_measure=measures[start].index,
                    end_measure=measure.index,
                    kind="PHRASE",
                    boundary_reason=(
                        reason if reason in self._SEGMENT_REASONS else "END"
                    ),
                    confidence=0.8 if reason in ("REST", "DOUBLE_BARLINE") else 0.6,
                )
            )
            start = position + 1

        return tuple(segments)

    def _patterns_in_range(
        self,
        analyses: dict[int, difficulty_module.MeasureAnalysis],
        start: int,
        end: int,
    ) -> tuple[TechnicalPattern, ...]:
        found: list[TechnicalPattern] = []
        for index in range(start, end + 1):
            analysis = analyses.get(index)
            if not analysis:
                continue
            for pattern in analysis.patterns:
                converted = TechnicalPattern(pattern.value)
                if converted not in found:
                    found.append(converted)
        return tuple(found)

    def _build_meta(
        self, raw: RawScore, raw_part: RawPart, timing: dict[int, tuple[float, float]]
    ) -> ScoreMeta:
        measures = raw_part.measures
        first = measures[0]

        time_signatures: list[tuple[int, TimeSignature]] = []
        tempo_map: list[tuple[int, float]] = []
        previous_time = None
        previous_tempo = None

        for measure in measures:
            signature = (measure.time_numerator, measure.time_denominator)
            if signature != previous_time:
                time_signatures.append(
                    (measure.index, TimeSignature(
                        numerator=measure.time_numerator,
                        denominator=measure.time_denominator))
                )
                previous_time = signature
            if measure.tempo_bpm != previous_tempo:
                tempo_map.append((measure.index, measure.tempo_bpm))
                previous_tempo = measure.tempo_bpm

        duration_sec = max((end for _, end in timing.values()), default=1.0)
        target_tempo = (
            self.options.target_tempo_bpm
            or raw.declared_tempo_bpm
            or first.tempo_bpm
            or 120.0
        )

        return ScoreMeta(
            title=raw.title,
            composer=raw.composer,
            arranger=raw.arranger,
            key=KeySignature(
                fifths=max(-7, min(7, first.key_fifths)),
                mode=Mode.MINOR if first.key_mode == "minor" else Mode.MAJOR,
            ),
            time_signatures=tuple(time_signatures),
            tempo_map=tuple(tempo_map),
            target_tempo_bpm=max(1.0, target_tempo),
            measure_count=len(measures),
            duration_sec=max(0.001, duration_sec),
            divisions=raw.divisions,
            ppq=raw.ppq,
            has_pickup=first.is_pickup,
            has_lyrics=raw.has_lyrics,
        )

    def _build_difficulty_summary(
        self, analyses: dict[int, difficulty_module.MeasureAnalysis]
    ) -> DifficultySummary | None:
        if not analyses:
            return None

        scores = sorted(a.score for a in analyses.values())
        mean = sum(scores) / len(scores)
        p90 = scores[min(len(scores) - 1, int(len(scores) * 0.9))]
        hardest = [
            index for index, _ in sorted(
                analyses.items(), key=lambda kv: kv[1].score, reverse=True
            )[:10]
        ]

        # Map the 0..10 measure scale onto the 0..8 syllabus grade. Provisional: the real
        # grade comes from piano-syllabus-classifier at P3-T02, which also recalibrates
        # the feature weights this derives from.
        global_grade = max(0.0, min(8.0, (mean * 0.6 + p90 * 0.4) * 0.8))

        return DifficultySummary(
            global_grade=round(global_grade, 3),
            mean_measure_difficulty=round(mean, 3),
            p90_measure_difficulty=round(p90, 3),
            hardest_measures=tuple(hardest),
            weights_version=difficulty_module.WEIGHTS_VERSION,
        )

    def _build_confidence(self, raw: RawScore) -> ConfidenceReport:
        """Confidence for a symbolic source.

        MusicXML that parsed cleanly is trustworthy by construction — there is no
        recognition step to be uncertain about. OMR-sourced documents get their real
        report from the pipeline; this only covers what the parser itself observed.
        """
        issues = []
        for warning in raw.warnings:
            issues.append(
                dict(code=IssueCode.PITCH_OUT_OF_RANGE, severity="WARNING", detail=warning)
            )

        from pilearn_worker.models.score_document import Issue, Severity

        typed_issues = tuple(
            Issue(
                code=item["code"],
                severity=Severity.WARNING,
                detail=item["detail"],
            )
            for item in issues
        )

        confidence = 1.0 - min(0.3, 0.03 * len(typed_issues))
        return ConfidenceReport(
            document_confidence=confidence,
            status=ReviewStatus.OK if confidence >= 0.85 else ReviewStatus.REVIEW_SUGGESTED,
            pages=(),
            issues=typed_issues,
        )


def build_document(raw: RawScore, options: BuildOptions) -> ScoreDocument:
    """Convenience entry point."""
    return ScoreDocumentBuilder(options).build(raw)
