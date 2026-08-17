"""Neutral intermediate representation between a notation parser and the ScoreDocument.

Why this layer exists
---------------------
`music21` is a large, slow dependency with its own object graph. If the ScoreDocument
builder consumed music21 objects directly, three things would follow:

  1. Every builder test would need music21 installed and would parse real files.
  2. Swapping music21 for partitura (planned — better MusicXML->array fidelity) would
     mean rewriting the builder, not just the adapter.
  3. Parser bugs and builder bugs would be indistinguishable in a stack trace.

So parsing is split in two:

    MusicXML --[music21 adapter]--> RawScore --[pure builder]--> ScoreDocument
              parser/musicxml_parser.py      parser/document_builder.py

`RawScore` is plain dataclasses with no third-party imports. Everything downstream of it
— hand assignment, difficulty, chunking, alignment — is pure and fully testable without a
notation library present.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class RawNote:
    """One note head as read from the source, before any analysis.

    `hand` is intentionally absent: hand assignment is a pedagogy decision made later by
    `pedagogy.hand_detector`, not something a parser should guess.
    """

    midi: int
    spelled: str                    # "C#4" — notated spelling, not derived from midi
    start_tick: int                 # absolute, from score start
    duration_ticks: int
    measure_index: int
    beat_offset: float              # beats from the bar line, 0-based
    staff: int                      # 1-based
    voice: int                      # 1-based
    finger: int | None = None
    finger_from_source: bool = False
    is_grace: bool = False
    is_ornament: bool = False
    is_chord_member: bool = False
    tie_start: bool = False
    tie_stop: bool = False
    articulations: tuple[str, ...] = ()
    dynamic: str | None = None
    confidence: float = 1.0


@dataclass(frozen=True, slots=True)
class RawRest:
    """Rests are not carried into the ScoreDocument as objects, but the builder needs
    them: a rest is the strongest single signal for a phrase boundary."""

    start_tick: int
    duration_ticks: int
    measure_index: int
    staff: int
    voice: int


@dataclass(frozen=True, slots=True)
class RawMeasure:
    index: int
    number: str
    start_tick: int
    end_tick: int
    time_numerator: int
    time_denominator: int
    key_fifths: int
    key_mode: str = "major"
    tempo_bpm: float = 120.0
    is_pickup: bool = False
    # Repeat structure, kept flat — the builder resolves it into playback order.
    starts_repeat: bool = False
    ends_repeat: bool = False
    repeat_times: int = 1
    volta: int | None = None
    jump: str | None = None         # "DC" | "DS" | "CODA" | "FINE"
    is_segno: bool = False
    is_coda: bool = False
    has_double_barline: bool = False
    confidence: float = 1.0

    @property
    def duration_ticks(self) -> int:
        return self.end_tick - self.start_tick


@dataclass(frozen=True, slots=True)
class RawPart:
    id: str
    name: str | None
    staff_count: int
    measures: tuple[RawMeasure, ...]
    notes: tuple[RawNote, ...]
    rests: tuple[RawRest, ...] = ()


@dataclass(frozen=True, slots=True)
class RawHarmony:
    measure_index: int
    beat: float
    root: str
    kind: str
    bass: str | None = None
    roman: str | None = None


@dataclass(slots=True)
class RawScore:
    """Everything a parser can state about a score without interpreting it."""

    title: str
    composer: str
    divisions: int                  # MusicXML divisions per quarter
    ppq: int                        # MIDI ticks per quarter
    parts: tuple[RawPart, ...]
    arranger: str | None = None
    harmony: tuple[RawHarmony, ...] = ()
    has_lyrics: bool = False
    # Populated when the source carried an explicit tempo; None means "infer".
    declared_tempo_bpm: float | None = None
    warnings: list[str] = field(default_factory=list)

    @property
    def measure_count(self) -> int:
        return max((len(p.measures) for p in self.parts), default=0)

    @property
    def note_count(self) -> int:
        return sum(len(p.notes) for p in self.parts)
