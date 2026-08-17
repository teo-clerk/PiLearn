"""MusicXML -> RawScore adapter.

The ONLY module allowed to import music21. Everything downstream consumes `RawScore`,
which is plain dataclasses — see models/raw.py for why that boundary exists.

Verified music21 behaviours this depends on (10.5.0)
-----------------------------------------------------
Confirmed empirically against real MusicXML rather than assumed, because each of these
would silently corrupt the output if guessed wrong:

* A single ``<part>`` carrying ``<staves>2</staves>`` — i.e. every piano score — is
  parsed into TWO ``PartStaff`` objects with ids ``P1-Staff1`` / ``P1-Staff2``.
  ``note.staffNumber`` is None; staff identity lives only in which PartStaff holds the
  note. We merge them back into one logical RawPart, because a grand staff is one
  instrument and hand assignment must see it that way.
* Pickup measures surface as ``measure.paddingLeft > 0`` with ``barDuration`` still
  reporting the full bar.
* Grace notes have ``quarterLength == 0.0`` and ``duration.isGrace``.
* When a measure has no explicit voice split, ``measure.voices`` is EMPTY and notes are
  direct children — iterating only ``measure.voices`` would silently drop every note in
  the common case.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

from pilearn_worker.models.raw import (
    RawHarmony,
    RawMeasure,
    RawNote,
    RawPart,
    RawRest,
    RawScore,
)

logger = logging.getLogger(__name__)

# MIDI ticks per quarter note. 480 divides cleanly by 2,3,4,5,6,8 — so triplets,
# quintuplets and 32nds all land on integers. 960 would too but doubles every number
# for no gain.
DEFAULT_PPQ = 480

# Grace notes have zero notated duration, but ScoreNote requires duration_ticks > 0 and
# playback needs something to sound. One 32nd is short enough to read as ornamental.
GRACE_NOTE_TICKS = DEFAULT_PPQ // 8

MIDI_MIN, MIDI_MAX = 21, 108


class MusicXmlParseError(RuntimeError):
    """The source could not be parsed into a usable RawScore."""


@dataclass
class _StaffGroup:
    """PartStaff objects that came from one logical `<part>`."""

    part_id: str
    name: str | None
    staves: list  # music21 PartStaff, ordered by staff number


def _quarter_to_ticks(quarter_length: float | Fraction, ppq: int = DEFAULT_PPQ) -> int:
    """Convert a music21 quarterLength to integer MIDI ticks.

    music21 uses Fraction for tuplets (1/3 of a quarter), so rounding must happen once,
    at the end. Rounding component-wise accumulates drift that shows up as a cursor
    slowly desynchronising across a long triplet passage.
    """
    return int(round(float(quarter_length) * ppq))


def _staff_number_of(part_staff, index: int) -> int:
    """Recover the staff number from a PartStaff id, falling back to position.

    music21 names them ``<partId>-Staff1``, ``<partId>-Staff2``. The suffix is
    authoritative when present because staff order in the score is not guaranteed to
    match list order for unusual layouts.
    """
    identifier = str(getattr(part_staff, "id", "") or "")
    marker = "-Staff"
    if marker in identifier:
        suffix = identifier.rsplit(marker, 1)[-1]
        if suffix.isdigit():
            return int(suffix)
    return index + 1


def _base_part_id(part_staff) -> str:
    identifier = str(getattr(part_staff, "id", "") or "")
    return identifier.rsplit("-Staff", 1)[0] if "-Staff" in identifier else identifier


def _group_staves(score) -> list[_StaffGroup]:
    """Regroup PartStaff objects back into the logical parts they came from."""
    groups: dict[str, _StaffGroup] = {}
    for index, part in enumerate(score.parts):
        base = _base_part_id(part) or f"P{index + 1}"
        group = groups.setdefault(
            base,
            _StaffGroup(part_id=base, name=None, staves=[]),
        )
        if group.name is None:
            name = getattr(part, "partName", None)
            if name:
                group.name = str(name)
        group.staves.append(part)

    for group in groups.values():
        group.staves.sort(key=lambda p: _staff_number_of(p, 0))
    return list(groups.values())


def _iter_voice_containers(measure):
    """Yield (voice_number, container) for a measure.

    Handles both shapes: an explicit ``<voice>`` split (measure.voices populated) and the
    common single-voice case where notes are direct children of the measure.
    """
    voices = list(measure.voices)
    if not voices:
        yield 1, measure
        return
    for position, voice in enumerate(voices, start=1):
        raw_id = getattr(voice, "id", None)
        # music21 uses the memory address as `id` when MusicXML gave no usable number,
        # so only trust it when it is a small integer-like value.
        try:
            number = int(str(raw_id))
            if number < 1 or number > 64:
                number = position
        except (TypeError, ValueError):
            number = position
        yield number, voice


def _spelled_name(pitch) -> str:
    """Notated spelling with octave, e.g. 'C#4'.

    music21's ``nameWithOctave`` renders sharps as '#' and flats as '-'. ScoreNote's
    pattern accepts both, and downstream key analysis compares the letter and accidental
    separately, so the '-' form is kept rather than rewritten.
    """
    return pitch.nameWithOctave


def _articulations_of(element) -> tuple[str, ...]:
    names = []
    for articulation in getattr(element, "articulations", ()) or ():
        name = getattr(articulation, "name", None)
        if name:
            names.append(str(name))
    for expression in getattr(element, "expressions", ()) or ():
        name = getattr(expression, "name", None)
        if name:
            names.append(str(name))
    return tuple(names)


def _is_ornament(element) -> bool:
    for expression in getattr(element, "expressions", ()) or ():
        if any(
            marker in expression.classes
            for marker in ("Trill", "Mordent", "Turn", "Ornament")
        ):
            return True
    return False


def _fingering_of(element) -> tuple[int | None, bool]:
    """Extract a fingering digit from the note's articulations, if the source had one."""
    for articulation in getattr(element, "articulations", ()) or ():
        if "Fingering" in articulation.classes:
            value = getattr(articulation, "fingerNumber", None)
            try:
                finger = int(value)
                if 1 <= finger <= 5:
                    return finger, True
            except (TypeError, ValueError):
                continue
    return None, False


class MusicXmlParser:
    """Parses MusicXML into the neutral RawScore representation."""

    def __init__(self, ppq: int = DEFAULT_PPQ) -> None:
        self.ppq = ppq

    def parse_file(self, path: Path) -> RawScore:
        try:
            from music21 import converter
        except ImportError as exc:  # pragma: no cover - environment guard
            raise MusicXmlParseError(f"music21 is required to parse MusicXML: {exc}") from exc

        if not path.is_file():
            raise MusicXmlParseError(f"file not found: {path}")

        try:
            score = converter.parse(str(path))
        except Exception as exc:
            raise MusicXmlParseError(f"music21 could not parse {path.name}: {exc}") from exc

        return self.parse_score(score, source_name=path.name)

    def parse_string(self, xml: str) -> RawScore:
        try:
            from music21 import converter
        except ImportError as exc:  # pragma: no cover
            raise MusicXmlParseError(f"music21 is required: {exc}") from exc
        try:
            score = converter.parse(xml, format="musicxml")
        except Exception as exc:
            raise MusicXmlParseError(f"music21 could not parse the payload: {exc}") from exc
        return self.parse_score(score, source_name="<string>")

    # ── Core ────────────────────────────────────────────────────────────────

    def parse_score(self, score, source_name: str = "") -> RawScore:
        warnings: list[str] = []

        groups = _group_staves(score)
        if not groups:
            raise MusicXmlParseError(f"{source_name}: score contains no parts")

        parts = [self._parse_group(group, warnings) for group in groups]
        parts = [p for p in parts if p.measures]
        if not parts:
            raise MusicXmlParseError(f"{source_name}: score contains no measures")

        metadata = getattr(score, "metadata", None)
        title = (getattr(metadata, "title", None) or "Untitled") if metadata else "Untitled"
        composer = (getattr(metadata, "composer", None) or "Unknown") if metadata else "Unknown"

        declared_tempo = None
        for mark in score.recurse().getElementsByClass("MetronomeMark"):
            if mark.number:
                declared_tempo = float(mark.number)
                break

        raw = RawScore(
            title=str(title),
            composer=str(composer),
            divisions=self.ppq,
            ppq=self.ppq,
            parts=tuple(parts),
            harmony=self._parse_harmony(score),
            has_lyrics=self._has_lyrics(score),
            declared_tempo_bpm=declared_tempo,
            warnings=warnings,
        )

        logger.info(
            "parsed %s: %d part(s), %d measures, %d notes%s",
            source_name, len(raw.parts), raw.measure_count, raw.note_count,
            f", {len(warnings)} warning(s)" if warnings else "",
        )
        return raw

    def _parse_group(self, group: _StaffGroup, warnings: list[str]) -> RawPart:
        measures: dict[int, RawMeasure] = {}
        notes: list[RawNote] = []
        rests: list[RawRest] = []

        # Measure start ticks come from the FIRST staff and are reused by the others, so
        # the staves of one instrument cannot drift apart.
        measure_start_ticks: dict[int, int] = {}
        running_tick = 0

        primary = group.staves[0]
        primary_measures = list(primary.getElementsByClass("Measure"))

        for index, m21_measure in enumerate(primary_measures):
            measure_start_ticks[index] = running_tick
            running_tick += _quarter_to_ticks(m21_measure.barDuration.quarterLength, self.ppq)

        for staff_index, part_staff in enumerate(group.staves):
            staff_number = _staff_number_of(part_staff, staff_index)
            staff_measures = list(part_staff.getElementsByClass("Measure"))

            if staff_index > 0 and len(staff_measures) != len(primary_measures):
                warnings.append(
                    f"staff {staff_number} has {len(staff_measures)} measures but staff 1 "
                    f"has {len(primary_measures)}; extra measures are ignored"
                )

            for index, m21_measure in enumerate(staff_measures):
                if index not in measure_start_ticks:
                    continue
                start_tick = measure_start_ticks[index]

                if index not in measures:
                    measures[index] = self._build_measure(
                        m21_measure, index, start_tick, self.ppq
                    )

                self._collect_events(
                    m21_measure, index, start_tick, staff_number, notes, rests, warnings
                )

        ordered = tuple(measures[i] for i in sorted(measures))
        return RawPart(
            id=group.part_id,
            name=group.name,
            staff_count=len(group.staves),
            measures=ordered,
            notes=tuple(sorted(notes, key=lambda n: (n.start_tick, n.midi))),
            rests=tuple(rests),
        )

    def _build_measure(self, m21_measure, index: int, start_tick: int, ppq: int) -> RawMeasure:
        time_signature = m21_measure.timeSignature or m21_measure.getContextByClass("TimeSignature")
        numerator = time_signature.numerator if time_signature else 4
        denominator = time_signature.denominator if time_signature else 4

        key_signature = m21_measure.keySignature or m21_measure.getContextByClass("KeySignature")
        fifths = key_signature.sharps if key_signature else 0
        mode = "major"
        if key_signature is not None and getattr(key_signature, "mode", None):
            mode = str(key_signature.mode)

        tempo_bpm = 120.0
        for mark in m21_measure.getElementsByClass("MetronomeMark"):
            if mark.number:
                tempo_bpm = float(mark.number)
                break

        # Pickup: music21 reports paddingLeft > 0, or the measure is short of a full bar.
        bar_quarters = float(m21_measure.barDuration.quarterLength)
        actual_quarters = float(m21_measure.duration.quarterLength)
        is_pickup = (
            float(getattr(m21_measure, "paddingLeft", 0) or 0) > 0
            or (index == 0 and 0 < actual_quarters < bar_quarters)
        )

        end_tick = start_tick + _quarter_to_ticks(bar_quarters, ppq)

        repeat = self._parse_repeats(m21_measure)

        return RawMeasure(
            index=index,
            number=str(m21_measure.number if m21_measure.number is not None else index + 1),
            start_tick=start_tick,
            end_tick=end_tick,
            time_numerator=numerator,
            time_denominator=denominator,
            key_fifths=fifths,
            key_mode=mode,
            tempo_bpm=tempo_bpm,
            is_pickup=is_pickup,
            **repeat,
        )

    def _parse_repeats(self, m21_measure) -> dict:
        starts = ends = False
        times = 1
        volta = None
        jump = None
        is_segno = is_coda = False
        double_bar = False

        for element in m21_measure.recurse():
            classes = element.classes
            if "Repeat" in classes:
                direction = getattr(element, "direction", None)
                if direction == "start":
                    starts = True
                elif direction == "end":
                    ends = True
                    # MusicXML `times` on a backward repeat counts TOTAL plays, and its
                    # default is 2 ("play it twice"). Defaulting to 1 makes every plain
                    # repeat barline a no-op, which is exactly what it did before.
                    times = max(2, int(getattr(element, "times", None) or 2))
            elif "DaCapo" in classes:
                jump = "DC"
            elif "DalSegno" in classes:
                jump = "DS"
            elif "Segno" in classes:
                is_segno = True
            elif "Coda" in classes:
                is_coda = True
            elif "Fine" in classes:
                jump = "FINE"

        spanners = getattr(m21_measure, "getSpannerSites", None)
        if spanners:
            for spanner in m21_measure.getSpannerSites():
                if "RepeatBracket" in spanner.classes:
                    number = getattr(spanner, "number", None)
                    try:
                        volta = int(str(number).split(",")[0].strip())
                    except (TypeError, ValueError):
                        volta = 1

        right_barline = getattr(m21_measure, "rightBarline", None)
        if right_barline is not None and getattr(right_barline, "type", None) in (
            "double", "final",
        ):
            double_bar = True

        return {
            "starts_repeat": starts,
            "ends_repeat": ends,
            "repeat_times": times,
            "volta": volta,
            "jump": jump,
            "is_segno": is_segno,
            "is_coda": is_coda,
            "has_double_barline": double_bar,
        }

    def _collect_events(
        self,
        m21_measure,
        measure_index: int,
        measure_start_tick: int,
        staff: int,
        notes: list[RawNote],
        rests: list[RawRest],
        warnings: list[str],
    ) -> None:
        time_signature = m21_measure.timeSignature or m21_measure.getContextByClass("TimeSignature")
        denominator = time_signature.denominator if time_signature else 4
        # In 6/8 the beat is an eighth. Beat offsets must respect that or "bar 3, beat 2"
        # points at the wrong place in every compound metre.
        ticks_per_beat = self.ppq * 4.0 / denominator

        # A pickup's notes are offset from the bar line by the padding.
        padding_ticks = _quarter_to_ticks(
            float(getattr(m21_measure, "paddingLeft", 0) or 0), self.ppq
        )

        for voice_number, container in _iter_voice_containers(m21_measure):
            for element in container.notesAndRests:
                offset_ticks = _quarter_to_ticks(element.offset, self.ppq) + padding_ticks
                start_tick = measure_start_tick + offset_ticks
                beat_offset = offset_ticks / ticks_per_beat if ticks_per_beat else 0.0

                if "Rest" in element.classes:
                    rests.append(
                        RawRest(
                            start_tick=start_tick,
                            duration_ticks=max(
                                1, _quarter_to_ticks(element.quarterLength, self.ppq)
                            ),
                            measure_index=measure_index,
                            staff=staff,
                            voice=voice_number,
                        )
                    )
                    continue

                is_grace = bool(getattr(element.duration, "isGrace", False))
                duration_ticks = _quarter_to_ticks(element.quarterLength, self.ppq)
                if is_grace or duration_ticks <= 0:
                    # Grace notes carry zero notated duration. They still need a sounding
                    # length for playback and a positive one for the ScoreDocument model.
                    duration_ticks = GRACE_NOTE_TICKS
                    is_grace = True

                pitches = list(getattr(element, "pitches", ()) or ())
                if not pitches:
                    continue
                is_chord = len(pitches) > 1

                tie = getattr(element, "tie", None)
                tie_type = getattr(tie, "type", None) if tie else None
                finger, from_source = _fingering_of(element)
                articulations = _articulations_of(element)
                ornament = _is_ornament(element)
                dynamic = None

                for pitch in pitches:
                    midi = int(pitch.midi)
                    if not (MIDI_MIN <= midi <= MIDI_MAX):
                        warnings.append(
                            f"measure {measure_index}: pitch {pitch.nameWithOctave} "
                            f"(midi {midi}) is outside the 88-key range and was dropped"
                        )
                        continue

                    notes.append(
                        RawNote(
                            midi=midi,
                            spelled=_spelled_name(pitch),
                            start_tick=start_tick,
                            duration_ticks=duration_ticks,
                            measure_index=measure_index,
                            beat_offset=round(beat_offset, 6),
                            staff=staff,
                            voice=voice_number,
                            finger=finger,
                            finger_from_source=from_source,
                            is_grace=is_grace,
                            is_ornament=ornament,
                            is_chord_member=is_chord,
                            tie_start=tie_type in ("start", "continue"),
                            tie_stop=tie_type in ("stop", "continue"),
                            articulations=articulations,
                            dynamic=dynamic,
                        )
                    )

    def _parse_harmony(self, score) -> tuple[RawHarmony, ...]:
        entries: list[RawHarmony] = []
        try:
            from music21 import harmony
        except ImportError:  # pragma: no cover
            return ()

        for symbol in score.recurse().getElementsByClass(harmony.ChordSymbol):
            measure = symbol.getContextByClass("Measure")
            if measure is None:
                continue
            root = symbol.root()
            if root is None:
                continue
            bass = symbol.bass()
            entries.append(
                RawHarmony(
                    measure_index=(measure.number or 1) - 1,
                    beat=float(symbol.offset) + 1.0,
                    root=root.name,
                    kind=str(symbol.chordKind or "major"),
                    bass=bass.name if bass is not None and bass != root else None,
                )
            )
        return tuple(entries)

    def _has_lyrics(self, score) -> bool:
        for element in score.recurse().notes:
            if getattr(element, "lyrics", None):
                return True
        return False


def parse_musicxml(path: Path, ppq: int = DEFAULT_PPQ) -> RawScore:
    """Convenience entry point."""
    return MusicXmlParser(ppq=ppq).parse_file(path)
