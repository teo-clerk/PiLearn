"""Canonical ScoreDocument — the single representation every surface reads from.

This module is the AUTHORITATIVE definition. The TypeScript client types and the Java
JSONB mapping are derived from the JSON Schema this emits:

    python -m pilearn_worker.models.score_document > schema/score-document.schema.json

Design decisions worth knowing before you extend this:

1. DUAL REPRESENTATION. The notation hierarchy (Part -> Measure -> Voice -> Note) is
   faithful to MusicXML and is what the pedagogy engine reasons over. The flat
   `timeline` is a denormalised, time-ordered projection of the same notes, used by
   playback and cursor alignment. They are generated together from one parse and are
   asserted consistent (see `validate_consistency`). Two representations is a
   deliberate cost: every consumer that wanted the other shape was otherwise going to
   rebuild it at runtime, which is exactly the client-side work Phase 2 removes.

2. TICKS ARE AUTHORITATIVE, SECONDS ARE DERIVED. Seconds depend on the tempo map and
   change when a learner practises at 60%. Never persist a decision made in seconds.

3. IDS ARE STABLE AND CONTENT-DERIVED. A note id must survive re-analysis so that
   attempt telemetry recorded last week still resolves. Ids never encode array indices.

4. NO OPTIONAL SOUP. A field that the pipeline can always compute is required. Optional
   means "genuinely absent from the source", not "we might not have gotten to it".
"""

from __future__ import annotations

import hashlib
import json
from enum import Enum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = "1.0"

# MIDI pitch bounds for an 88-key piano (A0..C8).
MIDI_MIN = 21
MIDI_MAX = 108

MidiPitch = Annotated[int, Field(ge=MIDI_MIN, le=MIDI_MAX)]
Confidence = Annotated[float, Field(ge=0.0, le=1.0)]


class Frozen(BaseModel):
    """A ScoreDocument is immutable once emitted; re-analysis produces a new revision."""

    model_config = ConfigDict(frozen=True, extra="forbid")


# ─────────────────────────────────────────────────────────────────────────────
# Enumerations
# ─────────────────────────────────────────────────────────────────────────────

class Hand(str, Enum):
    RIGHT = "RIGHT"
    LEFT = "LEFT"


class Mode(str, Enum):
    MAJOR = "major"
    MINOR = "minor"


class SourceKind(str, Enum):
    PDF = "PDF"
    IMAGE = "IMAGE"
    MUSICXML = "MUSICXML"
    MIDI = "MIDI"


class OmrEngine(str, Enum):
    HOMR = "homr"
    AUDIVERIS = "audiveris"
    NONE = "none"          # symbolic input, no OMR ran


class JumpKind(str, Enum):
    DA_CAPO = "DC"
    DAL_SEGNO = "DS"
    CODA = "CODA"
    FINE = "FINE"


class ReviewStatus(str, Enum):
    OK = "OK"
    REVIEW_SUGGESTED = "REVIEW_SUGGESTED"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"


class IssueCode(str, Enum):
    MEASURE_DURATION_MISMATCH = "MEASURE_DURATION_MISMATCH"
    STAFF_COUNT_ANOMALY = "STAFF_COUNT_ANOMALY"
    PAGE_DROPPED = "PAGE_DROPPED"
    KEY_INSTABILITY = "KEY_INSTABILITY"
    PITCH_OUT_OF_RANGE = "PITCH_OUT_OF_RANGE"
    UNRESOLVED_REPEAT = "UNRESOLVED_REPEAT"
    NO_TEMPO = "NO_TEMPO"
    HAND_ASSIGNMENT_AMBIGUOUS = "HAND_ASSIGNMENT_AMBIGUOUS"
    ALIGNMENT_LOW_CONFIDENCE = "ALIGNMENT_LOW_CONFIDENCE"


class Severity(str, Enum):
    ERROR = "ERROR"
    WARNING = "WARNING"


class TechnicalPattern(str, Enum):
    SCALE_RUN = "SCALE_RUN"
    ARPEGGIO = "ARPEGGIO"
    BROKEN_CHORD = "BROKEN_CHORD"
    OCTAVE_LEAP = "OCTAVE_LEAP"
    TRILL = "TRILL"
    CROSS_HAND = "CROSS_HAND"
    SYNCOPATION = "SYNCOPATION"
    POLYRHYTHM = "POLYRHYTHM"


# ─────────────────────────────────────────────────────────────────────────────
# Note level
# ─────────────────────────────────────────────────────────────────────────────

class ScoreNote(Frozen):
    """One notated note head.

    A chord is N ScoreNotes sharing `start_tick` and `chord_id`, not a nested object —
    assessment scores individual note heads, so a flat representation avoids unwrapping
    on the hot path.
    """

    id: str = Field(description="Stable, content-derived. See ScoreNote.make_id().")

    # Pitch
    midi: MidiPitch = Field(description="Sounding MIDI pitch.")
    spelled: str = Field(
        description="Notated spelling with octave, e.g. 'C#4'. Distinct from midi: "
        "C#4 and Db4 share midi 61 but are different notes on the page, and "
        "fingering/harmonic analysis depend on the spelling.",
        pattern=r"^[A-G][#b-]{0,2}-?\d$",
    )

    # Time — ticks authoritative, seconds derived from the tempo map
    start_tick: int = Field(ge=0)
    duration_ticks: int = Field(gt=0)
    start_sec: float = Field(ge=0.0)
    duration_sec: float = Field(gt=0.0)

    # Position within the measure, for pedagogy ("bar 12, beat 3")
    beat_offset: float = Field(
        ge=0.0, description="Beats from the measure start, 0-based. 0.0 == downbeat."
    )

    # Assignment
    hand: Hand
    staff: int = Field(ge=1, description="1-based MusicXML staff number.")
    voice: int = Field(ge=1, description="1-based MusicXML voice number.")

    # Performance hints
    finger: int | None = Field(default=None, ge=1, le=5)
    finger_source: Literal["score", "generated"] | None = None

    # Notation attributes
    chord_id: str | None = Field(
        default=None, description="Shared by every note head of one chord; None if single."
    )
    tied_from_id: str | None = None
    tied_to_id: str | None = None
    is_grace: bool = False
    is_ornament: bool = False
    articulations: tuple[str, ...] = ()
    dynamic: str | None = None

    # Provenance
    confidence: Confidence = Field(
        default=1.0,
        description="OMR confidence for this note. 1.0 for symbolic input.",
    )

    @staticmethod
    def make_id(measure_index: int, voice: int, start_tick: int, midi: int) -> str:
        """Stable id: survives re-analysis, never encodes an array position.

        Collision domain is (measure, voice, onset, pitch) — the same pitch cannot
        sound twice in one voice at one onset, so this is unique by construction.
        """
        raw = f"{measure_index}:{voice}:{start_tick}:{midi}"
        return f"n{hashlib.sha1(raw.encode()).hexdigest()[:12]}"


# ─────────────────────────────────────────────────────────────────────────────
# Voice / Measure / Part hierarchy
# ─────────────────────────────────────────────────────────────────────────────

class Voice(Frozen):
    """An independent melodic line within a staff.

    Voices matter for pedagogy: hand independence is measured between voices, and
    "practise the inner voice" is a real instruction that needs this level to exist.
    """

    number: int = Field(ge=1)
    staff: int = Field(ge=1)
    hand: Hand
    notes: tuple[ScoreNote, ...]

    @property
    def note_count(self) -> int:
        return len(self.notes)


class TimeSignature(Frozen):
    numerator: int = Field(gt=0)
    denominator: int = Field(gt=0)

    def __str__(self) -> str:
        return f"{self.numerator}/{self.denominator}"

    @property
    def beats_per_measure(self) -> float:
        return float(self.numerator)


class KeySignature(Frozen):
    fifths: int = Field(
        ge=-7, le=7, description="Sharp count; negative is flats. music21 convention."
    )
    mode: Mode = Mode.MAJOR


class RepeatInfo(Frozen):
    starts_repeat: bool = False
    ends_repeat: bool = False
    repeat_times: int = Field(default=1, ge=1)
    volta: int | None = Field(default=None, ge=1)
    jump: JumpKind | None = None
    is_segno: bool = False
    is_coda: bool = False


class MeasureDifficulty(Frozen):
    """Ten normalised features plus the scalar. See PRODUCT_SPEC.md §5.4."""

    note_density: Confidence = 0.0
    min_ioi: Confidence = 0.0
    max_span: Confidence = 0.0
    polyphony: Confidence = 0.0
    hand_independence: Confidence = 0.0
    accidental_rate: Confidence = 0.0
    leap_size: Confidence = 0.0
    rhythm_complexity: Confidence = 0.0
    position_shifts: Confidence = 0.0
    ornament_count: Confidence = 0.0

    score: float = Field(ge=0.0, le=10.0)
    patterns: tuple[TechnicalPattern, ...] = ()
    weights_version: str


class Measure(Frozen):
    """One bar, in NOTATION order (not performance order — see playback_order)."""

    index: int = Field(ge=0, description="0-based position in notation order.")
    number: str = Field(description="Printed number; a string because '12a' is legal.")

    start_tick: int = Field(ge=0)
    end_tick: int = Field(gt=0)
    start_sec: float = Field(ge=0.0)
    end_sec: float = Field(gt=0.0)

    time_signature: TimeSignature
    key_signature: KeySignature
    tempo_bpm: float = Field(gt=0.0)
    is_pickup: bool = False

    repeat: RepeatInfo = RepeatInfo()
    voices: tuple[Voice, ...]

    difficulty: MeasureDifficulty | None = None
    segment_id: str | None = None
    confidence: Confidence = 1.0

    @property
    def notes(self) -> tuple[ScoreNote, ...]:
        """Every note in the bar, time-ordered across voices."""
        merged = [note for voice in self.voices for note in voice.notes]
        return tuple(sorted(merged, key=lambda n: (n.start_tick, n.midi)))

    def notes_for_hand(self, hand: Hand) -> tuple[ScoreNote, ...]:
        return tuple(n for n in self.notes if n.hand == hand)

    @model_validator(mode="after")
    def _check_bounds(self) -> Measure:
        if self.end_tick <= self.start_tick:
            raise ValueError(f"measure {self.index}: end_tick must exceed start_tick")
        return self


class Part(Frozen):
    """A MusicXML part. Solo piano is one part with two staves."""

    id: str
    name: str | None = None
    staff_count: int = Field(ge=1)
    hand_mapping: dict[int, Hand] = Field(
        description="staff number -> hand. Typically {1: RIGHT, 2: LEFT}."
    )
    measures: tuple[Measure, ...]


# ─────────────────────────────────────────────────────────────────────────────
# Alignment — the flat projection
# ─────────────────────────────────────────────────────────────────────────────

class TimelineStep(Frozen):
    """One cursor position in PERFORMANCE order (repeats unrolled).

    This replaces the client-side OsmdArrayElement that cursor.service.ts spent 1,101
    lines computing per load. Every field is resolved here so the client does an O(1)
    lookup and nothing else.
    """

    index: int = Field(ge=0, description="Position in performance order.")
    measure_index: int = Field(ge=0, description="Notation measure this step belongs to.")
    osmd_cursor_index: int = Field(
        ge=0, description="OSMD cursor iterator position. The whole point of this type."
    )

    start_tick: int = Field(ge=0)
    duration_ticks: int = Field(gt=0)
    start_sec: float = Field(ge=0.0)

    note_ids: tuple[str, ...] = Field(description="ScoreNote ids sounding at this step.")
    pitches: tuple[MidiPitch, ...] = Field(description="Parallel to note_ids.")
    hands: tuple[Hand, ...] = Field(description="Parallel to note_ids.")

    is_repeat_jump: bool = False
    jump_target_index: int | None = None
    alignment_confidence: Confidence = 1.0

    @model_validator(mode="after")
    def _check_parallel_arrays(self) -> TimelineStep:
        if not (len(self.note_ids) == len(self.pitches) == len(self.hands)):
            raise ValueError(
                f"step {self.index}: note_ids/pitches/hands must be parallel "
                f"({len(self.note_ids)}/{len(self.pitches)}/{len(self.hands)})"
            )
        return self


class AlignmentIndex(Frozen):
    """Shipped to the client separately from the full document — this is the hot path."""

    ppq: int = Field(gt=0)
    steps: tuple[TimelineStep, ...]
    by_tick: dict[int, int] = Field(description="midi tick -> step index")
    by_measure: dict[int, int] = Field(description="measure index -> first step index")
    mean_confidence: Confidence = 1.0


# ─────────────────────────────────────────────────────────────────────────────
# Analysis and provenance
# ─────────────────────────────────────────────────────────────────────────────

class Segment(Frozen):
    """A phrase. Chunk boundaries are chosen from these, never from bar arithmetic."""

    id: str
    start_measure: int = Field(ge=0)
    end_measure: int = Field(ge=0)
    kind: Literal["PHRASE", "SECTION", "REPEAT_BLOCK"]
    boundary_reason: Literal[
        "CADENCE", "REST", "DOUBLE_BARLINE", "TEXTURE_CHANGE", "REPEAT", "END"
    ]
    cadence: Literal[
        "PERFECT", "IMPERFECT", "HALF", "DECEPTIVE", "PLAGAL"
    ] | None = None
    confidence: Confidence = 1.0


class HarmonyEntry(Frozen):
    measure: int = Field(ge=0)
    beat: float = Field(ge=0.0)
    root: str
    kind: str
    bass: str | None = None
    roman: str | None = None


class Issue(Frozen):
    code: IssueCode
    severity: Severity
    detail: str
    measure: int | None = None
    page: int | None = None


class PageResult(Frozen):
    page: int = Field(ge=1)
    engine: OmrEngine
    recognised: bool
    confidence: Confidence
    reason: str | None = None


class ConfidenceReport(Frozen):
    document_confidence: Confidence
    status: ReviewStatus
    pages: tuple[PageResult, ...] = ()
    issues: tuple[Issue, ...] = ()

    @property
    def dropped_pages(self) -> tuple[int, ...]:
        """Pages the OMR failed on. The legacy pipeline discarded these silently."""
        return tuple(p.page for p in self.pages if not p.recognised)


class SourceInfo(Frozen):
    kind: SourceKind
    input_hash: str = Field(description="SHA-256 of the uploaded bytes.")
    page_count: int | None = None
    omr_engine: OmrEngine = OmrEngine.NONE
    omr_engine_version: str | None = None
    pipeline_version: str


class ScoreMeta(Frozen):
    title: str
    composer: str
    arranger: str | None = None
    key: KeySignature
    time_signatures: tuple[tuple[int, TimeSignature], ...] = Field(
        description="(measure_index, signature) at each change."
    )
    tempo_map: tuple[tuple[int, float], ...] = Field(
        description="(measure_index, bpm) at each change."
    )
    target_tempo_bpm: float = Field(gt=0.0, description="Practice target; 100% of the ramp.")
    measure_count: int = Field(gt=0)
    duration_sec: float = Field(gt=0.0)
    divisions: int = Field(gt=0, description="MusicXML divisions per quarter note.")
    ppq: int = Field(gt=0, description="MIDI ticks per quarter note.")
    has_pickup: bool = False
    has_lyrics: bool = False


class DifficultySummary(Frozen):
    global_grade: float = Field(ge=0.0, le=8.0)
    mean_measure_difficulty: float = Field(ge=0.0, le=10.0)
    p90_measure_difficulty: float = Field(ge=0.0, le=10.0)
    hardest_measures: tuple[int, ...] = ()
    weights_version: str


# ─────────────────────────────────────────────────────────────────────────────
# Root
# ─────────────────────────────────────────────────────────────────────────────

class ScoreDocument(Frozen):
    """The canonical score. Immutable; re-analysis emits a new revision."""

    score_id: str
    revision: int = Field(ge=1)
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    analysis_version: str

    source: SourceInfo
    meta: ScoreMeta

    parts: tuple[Part, ...]

    playback_order: tuple[int, ...] = Field(
        description="Measure indices in performance order, repeats/voltas/D.C. unrolled."
    )
    alignment: AlignmentIndex

    segments: tuple[Segment, ...] = ()
    harmony: tuple[HarmonyEntry, ...] = ()
    difficulty: DifficultySummary | None = None
    confidence: ConfidenceReport

    # ── Convenience accessors ────────────────────────────────────────────────

    @property
    def measures(self) -> tuple[Measure, ...]:
        """Solo piano is single-part; multi-part concatenation is not meaningful here."""
        if len(self.parts) != 1:
            raise ValueError(
                f"measures shortcut requires exactly one part, found {len(self.parts)}. "
                "Iterate parts explicitly for multi-part scores."
            )
        return self.parts[0].measures

    def measure(self, index: int) -> Measure:
        for measure in self.measures:
            if measure.index == index:
                return measure
        raise KeyError(f"no measure with index {index}")

    def notes_in_range(self, start: int, end: int, hand: Hand | None = None):
        """Every note in measures [start, end] — the chunk-practice accessor."""
        for measure in self.measures:
            if start <= measure.index <= end:
                for note in measure.notes:
                    if hand is None or note.hand == hand:
                        yield note

    # ── Cross-representation invariants ──────────────────────────────────────

    @model_validator(mode="after")
    def validate_consistency(self) -> ScoreDocument:
        """Assert the hierarchy and the flat timeline describe the same music.

        Two representations can drift. Catching that here, at construction, is far
        cheaper than discovering it as a cursor desync during a practice session.
        """
        if not self.parts:
            raise ValueError("document must contain at least one part")

        measure_indices = {m.index for p in self.parts for m in p.measures}

        unknown = [i for i in self.playback_order if i not in measure_indices]
        if unknown:
            raise ValueError(f"playback_order references unknown measures: {unknown[:5]}")

        bad_steps = [
            s.index for s in self.alignment.steps if s.measure_index not in measure_indices
        ]
        if bad_steps:
            raise ValueError(f"alignment steps reference unknown measures: {bad_steps[:5]}")

        note_ids = {n.id for p in self.parts for m in p.measures for n in m.notes}
        dangling = [
            nid for s in self.alignment.steps for nid in s.note_ids if nid not in note_ids
        ]
        if dangling:
            raise ValueError(f"alignment references unknown note ids: {dangling[:5]}")

        if self.confidence.status is ReviewStatus.OK and self.confidence.dropped_pages:
            raise ValueError(
                f"status OK is inconsistent with dropped pages "
                f"{self.confidence.dropped_pages}. A dropped page means missing measures — "
                "this is exactly the legacy silent-partial-failure defect."
            )

        return self


def emit_json_schema() -> str:
    return json.dumps(ScoreDocument.model_json_schema(), indent=2)


if __name__ == "__main__":
    print(emit_json_schema())
