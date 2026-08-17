"""Shared fixtures — hand-written MusicXML covering the cases that break parsers.

These are raw MusicXML strings rather than music21-generated files on purpose: real input
comes from MuseScore and homr, and round-tripping through music21's exporter would test
music21 against itself and hide encoding quirks we actually have to survive.
"""

from __future__ import annotations

import pytest

pytest.importorskip("music21", reason="parser tests need music21")


def _wrap(measures: str, staves: int = 2, divisions: int = 4) -> str:
    clefs = (
        '<clef number="1"><sign>G</sign><line>2</line></clef>'
        '<clef number="2"><sign>F</sign><line>4</line></clef>'
        if staves == 2
        else '<clef><sign>G</sign><line>2</line></clef>'
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN"
  "http://www.musicxml.org/dtds/partwise.dtd">
<score-partwise version="4.0">
  <work><work-title>Test Piece</work-title></work>
  <identification><creator type="composer">Test Composer</creator></identification>
  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
  <part id="P1">{measures}</part>
</score-partwise>"""


def _attributes(divisions: int = 4, fifths: int = 0, beats: int = 4,
                beat_type: int = 4, staves: int = 2) -> str:
    clefs = (
        '<clef number="1"><sign>G</sign><line>2</line></clef>'
        '<clef number="2"><sign>F</sign><line>4</line></clef>'
        if staves == 2
        else '<clef><sign>G</sign><line>2</line></clef>'
    )
    return (
        f"<attributes><divisions>{divisions}</divisions>"
        f"<key><fifths>{fifths}</fifths></key>"
        f"<time><beats>{beats}</beats><beat-type>{beat_type}</beat-type></time>"
        f"<staves>{staves}</staves>{clefs}</attributes>"
    )


def _note(step: str, octave: int, duration: int, staff: int = 1, voice: int = 1,
          note_type: str = "quarter", chord: bool = False, alter: int | None = None,
          extra: str = "") -> str:
    alter_xml = f"<alter>{alter}</alter>" if alter is not None else ""
    chord_xml = "<chord/>" if chord else ""
    return (
        f"<note>{chord_xml}<pitch><step>{step}</step>{alter_xml}"
        f"<octave>{octave}</octave></pitch><duration>{duration}</duration>"
        f"<voice>{voice}</voice><type>{note_type}</type><staff>{staff}</staff>{extra}</note>"
    )


def _rest(duration: int, staff: int = 1, voice: int = 1, note_type: str = "quarter") -> str:
    return (
        f"<note><rest/><duration>{duration}</duration><voice>{voice}</voice>"
        f"<type>{note_type}</type><staff>{staff}</staff></note>"
    )


@pytest.fixture
def simple_two_staff() -> str:
    """Two bars, grand staff, one voice each. The baseline case."""
    measures = f"""
    <measure number="1">{_attributes()}
      {_note('C', 5, 4, staff=1)}{_note('D', 5, 4, staff=1)}
      {_note('E', 5, 4, staff=1)}{_note('F', 5, 4, staff=1)}
      <backup><duration>16</duration></backup>
      {_note('C', 3, 8, staff=2, voice=5, note_type='half')}
      {_note('G', 3, 8, staff=2, voice=5, note_type='half')}
    </measure>
    <measure number="2">
      {_note('G', 5, 8, staff=1, note_type='half')}
      {_note('A', 5, 8, staff=1, note_type='half')}
      <backup><duration>16</duration></backup>
      {_note('C', 3, 16, staff=2, voice=5, note_type='whole')}
    </measure>"""
    return _wrap(measures)


@pytest.fixture
def pickup_score() -> str:
    """Anacrusis: measure 0 is implicit and holds a single upbeat quarter."""
    measures = f"""
    <measure number="0" implicit="yes">{_attributes()}
      {_note('G', 4, 4, staff=1)}
    </measure>
    <measure number="1">
      {_note('C', 5, 16, staff=1, note_type='whole')}
      <backup><duration>16</duration></backup>
      {_note('C', 3, 16, staff=2, voice=5, note_type='whole')}
    </measure>"""
    return _wrap(measures)


@pytest.fixture
def chord_and_grace_score() -> str:
    """A tied chord followed by a grace note — both trip naive parsers."""
    measures = f"""
    <measure number="1">{_attributes()}
      {_note('C', 5, 8, staff=1, note_type='half',
             extra='<tie type="start"/><notations><tied type="start"/></notations>')}
      {_note('E', 5, 8, staff=1, note_type='half', chord=True)}
      {_note('G', 5, 8, staff=1, note_type='half', chord=True)}
      <note><grace slash="yes"/><pitch><step>F</step><alter>1</alter><octave>5</octave></pitch>
        <voice>1</voice><type>eighth</type><staff>1</staff></note>
      {_note('D', 5, 8, staff=1, note_type='half')}
      <backup><duration>16</duration></backup>
      {_note('C', 3, 16, staff=2, voice=5, note_type='whole')}
    </measure>"""
    return _wrap(measures)


@pytest.fixture
def multi_voice_score() -> str:
    """Two independent voices on the treble staff."""
    measures = f"""
    <measure number="1">{_attributes()}
      {_note('C', 5, 8, staff=1, voice=1, note_type='half')}
      {_note('E', 5, 8, staff=1, voice=1, note_type='half')}
      <backup><duration>16</duration></backup>
      {_note('G', 4, 4, staff=1, voice=2)}
      {_note('A', 4, 4, staff=1, voice=2)}
      {_note('B', 4, 4, staff=1, voice=2)}
      {_note('C', 5, 4, staff=1, voice=2)}
      <backup><duration>16</duration></backup>
      {_note('C', 3, 16, staff=2, voice=5, note_type='whole')}
    </measure>"""
    return _wrap(measures)


@pytest.fixture
def repeat_score() -> str:
    """Bars 1-2 repeated: performance order must be 0,1,0,1,2."""
    measures = f"""
    <measure number="1">{_attributes()}
      <barline location="left"><bar-style>heavy-light</bar-style>
        <repeat direction="forward"/></barline>
      {_note('C', 5, 16, staff=1, note_type='whole')}
    </measure>
    <measure number="2">
      {_note('D', 5, 16, staff=1, note_type='whole')}
      <barline location="right"><bar-style>light-heavy</bar-style>
        <repeat direction="backward"/></barline>
    </measure>
    <measure number="3">
      {_note('E', 5, 16, staff=1, note_type='whole')}
    </measure>"""
    return _wrap(measures, staves=1)


@pytest.fixture
def rest_boundary_score() -> str:
    """A bar of rest at bar 4 — a phrase boundary the chunker should honour."""
    bars = [_attributes()]
    for index in range(8):
        content = _rest(16, staff=1, note_type="whole") if index == 3 else (
            _note("C", 5, 8, staff=1, note_type="half")
            + _note("D", 5, 8, staff=1, note_type="half")
        )
        prefix = _attributes() if index == 0 else ""
        bars.append(f'<measure number="{index + 1}">{prefix}{content}</measure>')
    return _wrap("".join(bars[1:]), staves=1)


@pytest.fixture
def compound_metre_score() -> str:
    """6/8 — the beat is an eighth, so beat offsets must not assume a quarter."""
    measures = f"""
    <measure number="1">{_attributes(beats=6, beat_type=8, staves=1)}
      {_note('C', 5, 2, staff=1, note_type='eighth')}
      {_note('D', 5, 2, staff=1, note_type='eighth')}
      {_note('E', 5, 2, staff=1, note_type='eighth')}
      {_note('F', 5, 2, staff=1, note_type='eighth')}
      {_note('G', 5, 2, staff=1, note_type='eighth')}
      {_note('A', 5, 2, staff=1, note_type='eighth')}
    </measure>"""
    return _wrap(measures, staves=1)


@pytest.fixture
def out_of_range_score() -> str:
    """Contains a pitch below A0 — must be dropped with a warning, not crash."""
    measures = f"""
    <measure number="1">{_attributes(staves=1)}
      {_note('C', 5, 8, staff=1, note_type='half')}
      {_note('C', -1, 8, staff=1, note_type='half')}
    </measure>"""
    return _wrap(measures, staves=1)
