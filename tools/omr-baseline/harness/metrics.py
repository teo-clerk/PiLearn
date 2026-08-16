"""Deterministic metric extraction from a packed score archive.

The baseline harness compares pipeline runs to each other (regression) and to
hand-entered ground truth (accuracy). Both need a metric set that is:

  * deterministic  — same input, same numbers, every run, on every machine
  * structural     — describes what the OMR actually recognised, not how it looks
  * diffable       — a changed number points at a specific kind of regression

Anything non-deterministic (wall time, temp paths, engine log text) is recorded
in the run report but explicitly excluded from the golden comparison.

Runs inside the OMR toolchain container against ~/shared-venv, which already
provides music21 and pretty_midi.
"""

from __future__ import annotations

import hashlib
import json
import zipfile
from collections import Counter
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

# Members the legacy pdf2pack.sh writes into the archive.
MUSICXML_SUFFIXES = (".musicxml", ".xml")
MIDI_SUFFIXES = (".midi", ".mid")


# ─────────────────────────────────────────────────────────────────────────────
# Result containers
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class MusicXmlMetrics:
    """Structural facts about the recognised notation."""

    part_count: int = 0
    staff_count: int = 0
    measure_count: int = 0
    note_count: int = 0
    rest_count: int = 0
    chord_count: int = 0
    tied_note_count: int = 0
    grace_note_count: int = 0
    distinct_pitch_count: int = 0
    pitch_min_midi: int | None = None
    pitch_max_midi: int | None = None
    key_signatures: list[str] = field(default_factory=list)
    time_signatures: list[str] = field(default_factory=list)
    tempo_marks: list[float] = field(default_factory=list)
    has_fingering: bool = False
    has_harmony: bool = False
    has_lyrics: bool = False
    repeat_count: int = 0
    volta_count: int = 0
    # Per-measure note counts. The single most useful regression signal: it
    # localises a change to a measure range instead of a whole-document delta.
    measure_note_counts: list[int] = field(default_factory=list)
    # Order-independent fingerprint of every (measure, offset, midi) triple.
    content_hash: str = ""
    parse_error: str | None = None


@dataclass
class MidiMetrics:
    """Structural facts about the rendered performance."""

    track_count: int = 0
    note_count: int = 0
    ppq: int | None = None
    duration_sec: float = 0.0
    pitch_min_midi: int | None = None
    pitch_max_midi: int | None = None
    distinct_pitch_count: int = 0
    tempo_changes: int = 0
    initial_tempo_bpm: float | None = None
    time_signature_changes: int = 0
    # Coarse pitch-class histogram; shifts when the key is misread.
    pitch_class_histogram: list[int] = field(default_factory=lambda: [0] * 12)
    content_hash: str = ""
    parse_error: str | None = None


@dataclass
class ArchiveMetrics:
    """What the pipeline actually produced."""

    exists: bool = False
    size_bytes: int = 0
    members: list[str] = field(default_factory=list)
    has_musicxml: bool = False
    has_midi: bool = False
    has_pdf: bool = False
    has_metadata: bool = False
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class ScoreMetrics:
    archive: ArchiveMetrics
    musicxml: MusicXmlMetrics
    midi: MidiMetrics

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


# ─────────────────────────────────────────────────────────────────────────────
# Extraction
# ─────────────────────────────────────────────────────────────────────────────

def _round(value: float, places: int = 3) -> float:
    """Round for cross-machine stability — float noise must not fail a diff."""
    return float(f"{value:.{places}f}")


def _hash_events(events: list[tuple]) -> str:
    """Order-independent fingerprint of a note-event set."""
    payload = "|".join(sorted(f"{a}:{b}:{c}" for a, b, c in events))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]


def extract_archive(zip_path: Path, extract_to: Path) -> ArchiveMetrics:
    metrics = ArchiveMetrics()
    if not zip_path.is_file():
        return metrics

    metrics.exists = True
    metrics.size_bytes = zip_path.stat().st_size
    extract_to.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zip_path) as archive:
        metrics.members = sorted(archive.namelist())
        archive.extractall(extract_to)

    lowered = [m.lower() for m in metrics.members]
    metrics.has_musicxml = any(m.endswith(MUSICXML_SUFFIXES) for m in lowered)
    metrics.has_midi = any(m.endswith(MIDI_SUFFIXES) for m in lowered)
    metrics.has_pdf = any(m.endswith(".pdf") for m in lowered)
    metrics.has_metadata = any(m.endswith("metadata.json") for m in lowered)

    metadata_file = extract_to / "metadata.json"
    if metadata_file.is_file():
        try:
            metrics.metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            metrics.metadata = {"_parse_error": str(exc)}

    return metrics


def extract_musicxml(path: Path) -> MusicXmlMetrics:
    metrics = MusicXmlMetrics()
    try:
        from music21 import converter, expressions, harmony, note as m21note, tempo
    except ImportError as exc:  # pragma: no cover - environment guard
        metrics.parse_error = f"music21 unavailable: {exc}"
        return metrics

    try:
        score = converter.parse(str(path), format="musicxml")
    except Exception as exc:  # music21 raises a wide family of parse errors
        metrics.parse_error = f"{type(exc).__name__}: {exc}"
        return metrics

    parts = list(score.parts)
    metrics.part_count = len(parts)

    staves: set[tuple[int, int]] = set()
    pitches: list[int] = []
    events: list[tuple] = []
    per_measure: Counter[int] = Counter()

    for part_index, part in enumerate(parts):
        measures = list(part.getElementsByClass("Measure"))
        metrics.measure_count = max(metrics.measure_count, len(measures))

        for measure in measures:
            number = measure.measureNumber or 0
            staves.add((part_index, getattr(measure, "staffNumber", 0) or 0))

            for element in measure.recurse().notesAndRests:
                if isinstance(element, m21note.Rest):
                    metrics.rest_count += 1
                    continue

                element_pitches = getattr(element, "pitches", None) or ()
                if len(element_pitches) > 1:
                    metrics.chord_count += 1
                if getattr(element, "isGrace", False):
                    metrics.grace_note_count += 1
                if getattr(element, "tie", None) is not None:
                    metrics.tied_note_count += 1

                offset = _round(float(element.offset), 4)
                for pitch in element_pitches:
                    midi_value = int(pitch.midi)
                    pitches.append(midi_value)
                    events.append((number, offset, midi_value))
                    metrics.note_count += 1
                    per_measure[number] += 1

                if not metrics.has_fingering:
                    for articulation in getattr(element, "articulations", ()):
                        if articulation.classes and "Fingering" in articulation.classes:
                            metrics.has_fingering = True
                            break
                if not metrics.has_lyrics and getattr(element, "lyrics", None):
                    metrics.has_lyrics = True

            for element in measure.recurse():
                classes = element.classes
                if "RepeatMark" in classes or "Repeat" in classes:
                    metrics.repeat_count += 1
                if "RepeatBracket" in classes:
                    metrics.volta_count += 1

    metrics.staff_count = len(staves)

    for chord_symbol in score.recurse().getElementsByClass(harmony.ChordSymbol):
        metrics.has_harmony = True
        break

    for key_signature in score.recurse().getElementsByClass("KeySignature"):
        metrics.key_signatures.append(str(key_signature.sharps))
    for time_signature in score.recurse().getElementsByClass("TimeSignature"):
        metrics.time_signatures.append(time_signature.ratioString)
    for mark in score.recurse().getElementsByClass(tempo.MetronomeMark):
        if mark.number:
            metrics.tempo_marks.append(_round(float(mark.number), 2))

    # Deduplicate while preserving order — a score legitimately changes key.
    metrics.key_signatures = list(dict.fromkeys(metrics.key_signatures))
    metrics.time_signatures = list(dict.fromkeys(metrics.time_signatures))
    metrics.tempo_marks = list(dict.fromkeys(metrics.tempo_marks))

    if pitches:
        metrics.pitch_min_midi = min(pitches)
        metrics.pitch_max_midi = max(pitches)
        metrics.distinct_pitch_count = len(set(pitches))

    if per_measure:
        highest = max(per_measure)
        metrics.measure_note_counts = [per_measure.get(i, 0) for i in range(highest + 1)]

    metrics.content_hash = _hash_events(events)
    return metrics


def extract_midi(path: Path) -> MidiMetrics:
    metrics = MidiMetrics()
    try:
        import pretty_midi
    except ImportError as exc:  # pragma: no cover - environment guard
        metrics.parse_error = f"pretty_midi unavailable: {exc}"
        return metrics

    try:
        midi = pretty_midi.PrettyMIDI(str(path))
    except Exception as exc:
        metrics.parse_error = f"{type(exc).__name__}: {exc}"
        return metrics

    metrics.track_count = len(midi.instruments)
    metrics.ppq = int(midi.resolution)
    metrics.duration_sec = _round(midi.get_end_time(), 3)

    pitches: list[int] = []
    events: list[tuple] = []
    histogram = [0] * 12

    for track_index, instrument in enumerate(midi.instruments):
        for midi_note in instrument.notes:
            pitches.append(midi_note.pitch)
            histogram[midi_note.pitch % 12] += 1
            # Quantise onsets to 1 ms so float drift cannot break a hash match.
            events.append((track_index, int(round(midi_note.start * 1000)), midi_note.pitch))

    metrics.note_count = len(pitches)
    metrics.pitch_class_histogram = histogram

    if pitches:
        metrics.pitch_min_midi = min(pitches)
        metrics.pitch_max_midi = max(pitches)
        metrics.distinct_pitch_count = len(set(pitches))

    tempo_times, tempi = midi.get_tempo_changes()
    metrics.tempo_changes = len(tempi)
    if len(tempi) > 0:
        metrics.initial_tempo_bpm = _round(float(tempi[0]), 2)
    metrics.time_signature_changes = len(midi.time_signature_changes)

    metrics.content_hash = _hash_events(events)
    return metrics


def extract_all(zip_path: Path, work_dir: Path) -> ScoreMetrics:
    """Extract every metric family from a packed archive."""
    archive = extract_archive(zip_path, work_dir)

    musicxml = MusicXmlMetrics()
    midi = MidiMetrics()

    if archive.exists:
        for candidate in sorted(work_dir.iterdir()):
            suffix = candidate.suffix.lower()
            if suffix in MUSICXML_SUFFIXES and not musicxml.content_hash:
                musicxml = extract_musicxml(candidate)
            elif suffix in MIDI_SUFFIXES and not midi.content_hash:
                midi = extract_midi(candidate)

    return ScoreMetrics(archive=archive, musicxml=musicxml, midi=midi)
