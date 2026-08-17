# PHASE2_KICKOFF — Canonical ScoreDocument & OMR Pipeline Hardening

Implementation guide for Phase 2. The schema is **code**, not prose:
[`worker/pilearn_worker/models/score_document.py`](../worker/pilearn_worker/models/score_document.py)
is authoritative. This document explains its design and the migration off the shell pipeline.

Roadmap tasks: **P2-T01 … P2-T24** in [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md).

---

## 1. The `ScoreDocument` domain

### 1.1 Why a canonical document at all

Today four surfaces each derive their own understanding of a score:

| Surface | Derives from | Cost |
|---|---|---|
| Engraving | MusicXML, parsed by OSMD | — |
| Playback | MIDI, parsed by `@tonejs/midi` | — |
| Cursor tracking | **Both**, aligned at runtime | 1,101 LOC in `cursor.service.ts`, per load |
| Pedagogy | *does not exist* | — |

Nothing guarantees these agree. `cursor.service.verify()` computes a quality score and nothing
acts on it. The canonical document collapses this: one parse, server-side, once per revision.

### 1.2 Hierarchy

The user-facing requirement was an explicit Part / Measure / Voice / Staff hierarchy. That is
what the model provides — with one deliberate addition.

```
ScoreDocument
├── source          SourceInfo        provenance: engine, version, input hash
├── meta            ScoreMeta         key, tempo map, divisions, ppq, target tempo
├── parts           Part[]            solo piano = 1 part, 2 staves
│   └── measures    Measure[]         NOTATION order
│       ├── time_signature / key_signature / tempo_bpm / repeat
│       ├── voices  Voice[]           ← independent lines, carries staff + hand
│       │   └── notes  ScoreNote[]    pitch, ticks, beat offset, hand, finger
│       └── difficulty  MeasureDifficulty
├── playback_order  int[]             PERFORMANCE order, repeats unrolled
├── alignment       AlignmentIndex    ← the flat projection
├── segments        Segment[]         phrases; chunk boundaries come from here
├── harmony         HarmonyEntry[]
├── difficulty      DifficultySummary
└── confidence      ConfidenceReport
```

**`Staff` is an attribute, not a level.** MusicXML models staff as a property of note and voice,
not as a container — a single voice can cross staves (Debussy does it constantly, and
`clair-de-lune` is in the fixture corpus for exactly that reason). Promoting staff to a container
would force cross-staff voices to be split and rejoined, which is precisely the bug class we are
trying to eliminate. `ScoreNote.staff` and `Voice.staff` carry it; `Part.hand_mapping` maps staff
number to hand.

### 1.3 The dual representation

`Measure.voices` (hierarchy) and `alignment.steps` (flat, time-ordered, performance order) are
two projections of the same notes, generated from one parse.

| Consumer | Reads | Why |
|---|---|---|
| Pedagogy engine | hierarchy | "hand independence between voices in bars 12–16" needs structure |
| Difficulty analysis | hierarchy | features are per-measure, per-voice |
| Playback + cursor | `alignment` | needs performance order and O(1) tick lookup |
| Assessment | `alignment` → `note_ids` → hierarchy | resolves a played note back to its bar |

Two representations is a real cost. It is paid deliberately: every consumer that wanted the other
shape was otherwise rebuilding it at runtime — which is the client-side work Phase 2 removes.

The cost is contained by `ScoreDocument.validate_consistency`, which runs at construction and
rejects drift. Verified behaviour:

```
dangling note id          -> alignment references unknown note ids: ['nDOESNOTEXIST']
bad playback_order        -> playback_order references unknown measures: [99]
OK status + dropped page  -> status OK is inconsistent with dropped pages (2,)
```

**That third invariant is the point of Phase 2.** A document that claims to be fine while having
dropped a page cannot be constructed. The legacy silent-partial-failure defect becomes
unrepresentable rather than merely discouraged.

### 1.4 Note-level attributes

```python
ScoreNote(
    id           = "n4f2a91c3e8b7",   # content-derived, survives re-analysis
    midi         = 61,                 # sounding pitch
    spelled      = "C#4",              # notated spelling — NOT derivable from midi
    start_tick   = 1920,               # authoritative
    duration_ticks = 480,
    start_sec    = 2.0,                # derived from the tempo map
    duration_sec = 0.5,
    beat_offset  = 1.0,                # beats from the bar line — "bar 12, beat 2"
    hand         = Hand.RIGHT,
    staff        = 1,
    voice        = 1,
    finger       = 3,
    finger_source= "generated",        # score-supplied vs pianoplayer
    chord_id     = "c8891fe",          # shared across the chord's note heads
    confidence   = 0.94,               # per-note OMR confidence
)
```

Four decisions worth defending:

- **`spelled` is separate from `midi`.** C♯4 and D♭4 share MIDI 61 but are different notes on the
  page. Fingering, key analysis and the review UI all need the spelling.
- **Ticks authoritative, seconds derived.** Seconds change when a learner practises at 60 %.
  Never persist a decision made in seconds.
- **Chords are flat, not nested.** Assessment scores individual note heads; `chord_id` groups them
  when needed. Nesting would force unwrapping on the hot path.
- **Ids are content-derived from `(measure, voice, onset, pitch)`.** Never array indices — a
  practice attempt recorded last week must still resolve after re-analysis.

### 1.5 Alignment metadata

`TimelineStep` is what replaces `OsmdArrayElement`:

```python
TimelineStep(
    index                = 42,          # performance order
    measure_index        = 17,          # notation measure
    osmd_cursor_index    = 51,          # ← OSMD iterator position, precomputed
    start_tick           = 32640,
    note_ids             = ("n4f2a...", "n88b1..."),
    pitches              = (61, 64),    # parallel arrays, length-checked
    hands                = (RIGHT, RIGHT),
    is_repeat_jump       = False,
    alignment_confidence = 0.97,        # per-step, from Smith-Waterman
)
```

Differences from the client-side type it replaces:

| | `OsmdArrayElement` (legacy) | `TimelineStep` |
|---|---|---|
| Computed | in-browser, per load | server-side, once per revision |
| Nullable fields | `osmdPitches`, `midiTicks`, `midiTicksDuration`, `target`… | none on the hot path |
| Confidence | one global `verify()` score, unused | per step, actionable |
| Client cost | multi-pass search + Smith-Waterman | `by_tick[tick]` → O(1) |

### 1.6 Deriving the other language bindings

Python is authoritative. Do not hand-write the TypeScript.

```bash
cd worker
python -m pilearn_worker.models.score_document > ../schema/score-document.schema.json

cd ..
npx json-schema-to-typescript schema/score-document.schema.json \
    -o frontend/src/app/core/score/score-document.model.ts
```

On the Java side the document is a JSONB column. Do **not** mirror the full type graph in Java
records — the backend stores and serves it, and only reads `meta`, `confidence` and `difficulty`.
Map those three as records; keep the rest as `JsonNode`. Mirroring the whole graph creates a
third definition that will drift.

---

## 2. Transition: shell pipeline → typed FastAPI worker

### 2.1 What is actually wrong today

From the audit, ordered by consequence:

| # | Defect | Consequence |
|---|---|---|
| 1 | `pdf2pack.sh` fails only when **every** page fails | A 12-page score silently becomes 9 pages |
| 2 | Failed pages are dropped from the `relieur` concat with no record | Missing measures, no signal |
| 3 | `CloudRunJobService` never attaches `scoreId`/`s3Key` overrides | Args are logged, not passed |
| 4 | Worker drains **all** `PENDING` rows, `System.exit(0)` | Double-processing under concurrency |
| 5 | Result overwrites the upload key | The original PDF is destroyed by the job consuming it |
| 6 | `musicxml → mid → musicxml` round-trip | Destroys voicing, ties, spelling |
| 7 | Unquoted globs; `exit 1` commented out; stray `n start` | Breaks on real filenames |

Defects 1, 2 and 5 are data-destroying. They come first.

### 2.2 Strangler sequence

Do not rewrite the pipeline in one pass. Replace it stage by stage behind a stable interface,
with the baseline harness green at every step.

```
Step 0  Baseline locked          golden snapshots committed        [P1-T11..13]
        ├─ ./tools/omr-baseline/run-baseline.sh --promote
        └─ every later step re-runs `--check`

Step 1  Worker skeleton          FastAPI + typed Stage protocol    [P2-T01,02]
        └─ /health, /ingest; stages still shell out to the SAME scripts.
           Behaviour identical, harness must stay green. This is the safety net.

Step 2  Port P1-P4               rasterise, recognise, merge, normalise  [P2-T03..06]
        └─ one stage per PR; harness --check after each.
           P2-T04 lands defect #1+#2: a dropped page fails the document.
           P2-T06 lands defect #6: round-trip through .mscz only.
           EXPECT the golden to change at P2-T06 — voicing improves. Re-promote
           only after a human confirms the diff is an improvement.

Step 3  Validation gate          ConfidenceReport + status bands   [P2-T07]
        └─ the first stage with no legacy equivalent. Purely additive.

Step 4  Second engine            Audiveris + arbitration           [P2-T08,09]

Step 5  Document build           hands, repeats, alignment, emit   [P2-T10..14]
        └─ the ScoreDocument becomes real here.

Step 6  Orchestration            queue, atomic claim, lease, DLQ   [P2-T15..19]
        └─ defects #3, #4, #5. Legacy Cloud Run job path deleted.

Step 7  Client cutover           ScoreIndexService                 [P2-T22..24]
        └─ cursor.service.ts: 1,101 -> <=150 LOC
```

Steps 1–5 ship without touching the frontend. The client keeps its own alignment until Step 7,
so ingestion can be replaced entirely before any user-visible change.

### 2.3 The stage contract

Every stage is pure, cached and independently testable:

```python
class Stage(Protocol):
    name: str
    version: str          # bump invalidates the cache for this stage only

    def run(self, ctx: StageContext, inp: StageInput) -> StageOutput: ...
```

- Cache key `(input_hash, stage_version, params)`. Changing difficulty weights re-runs P8, not OMR.
- Stages emit progress events; the orchestrator persists and fans them out over SSE.
- A stage raises `StageError(code: IssueCode, severity, detail)` — never a bare exception, never
  a silent partial result.

### 2.4 Killing the dropped-page defect

This is the single most important behavioural change in Phase 2.

**Legacy:**

```bash
for FILE in $FILES; do
  if poetry run homr "$FILE" > /dev/null 2>&1; then
    XMLFILES="$XMLFILES $XML_FILE"       # success: include
  else
    HOMR_ERRORS=$((HOMR_ERRORS + 1))     # failure: warn, then CONTINUE ANYWAY
  fi
done
if [ "$HOMR_TOTAL" -gt 0 ] && [ "$HOMR_SUCCESSES" -eq 0 ]; then
  exit 1                                  # only fails if ALL pages failed
fi
```

**Replacement — every page accounted for, always:**

```python
def recognise(self, ctx: StageContext, pages: list[Path]) -> RecogniseOutput:
    results: list[PageResult] = []

    for page_number, image in enumerate(pages, start=1):
        try:
            musicxml, confidence = self.engine.recognise(image)
            results.append(PageResult(page=page_number, engine=self.engine.id,
                                      recognised=True, confidence=confidence))
        except EngineError as exc:
            # Recorded, never discarded. A page we could not read is a page whose
            # measures are missing from the score.
            results.append(PageResult(page=page_number, engine=self.engine.id,
                                      recognised=False, confidence=0.0, reason=str(exc)))
            ctx.emit_issue(IssueCode.PAGE_DROPPED, Severity.ERROR,
                           f"page {page_number}: {exc}", page=page_number)

    if not any(r.recognised for r in results):
        raise StageError(IssueCode.PAGE_DROPPED, Severity.ERROR, "no page could be recognised")

    return RecogniseOutput(pages=tuple(results), ...)
```

The pipeline continues so the user still gets a reviewable partial result — but the ERROR issue
propagates into `ConfidenceReport`, which forces `REVIEW_REQUIRED`, which the
`validate_consistency` invariant then makes impossible to contradict.

**Three independent layers stop this defect:** the stage records it, the confidence gate acts on
it, and the model refuses to represent a document that claims otherwise. Any one of them can be
bypassed by a future change; all three cannot be, silently.

### 2.5 Orchestration

Replaces `CloudRunJobService` + `WorkloadProcessingService` (defects #3, #4).

```
POST /score/{id}/ingestion
  └─ INSERT ingestion_job(status=QUEUED, idempotency_key=sha256(hash‖versions))
  └─ enqueue ONE message carrying that job id
        │
        ▼
   worker POST /ingest {jobId}
        ├─ atomic claim:
        │    UPDATE ingestion_job SET status='RUNNING', lease_until=now()+15min,
        │           attempt=attempt+1
        │    WHERE id=:jobId AND status IN ('QUEUED','LEASE_EXPIRED')
        │    RETURNING *          -- 0 rows => already claimed => ack, exit
        ├─ run P1..P9, emitting events
        └─ COMPLETED | REVIEW_REQUIRED | FAILED(code, stage)
              retryable && attempt < 3  -> QUEUED with backoff
              otherwise                 -> FAILED + dead-letter + notify owner
```

Rules, each mapping to a specific legacy defect:

| Rule | Fixes |
|---|---|
| One message = one job | #4 double-processing |
| Idempotency key on `(input_hash, versions)` | duplicate submissions |
| Atomic claim with `RETURNING` | #4 concurrent claims |
| Lease + sweeper for expired `RUNNING` | orphaned jobs on worker death |
| Long-lived HTTP service, no `System.exit` | #4 untestable exit |
| `raw/` and `derived/` prefixes, raw immutable | #5 destroyed originals |

### 2.6 Definition of done for Phase 2

- [ ] Every page of every fixture is accounted for in `ConfidenceReport.pages`
- [ ] A deliberately corrupted page yields `PAGE_DROPPED` + `REVIEW_REQUIRED`, never a short score
- [ ] `run-baseline.sh --check` green, or every diff reviewed and re-promoted with justification
- [ ] Concurrency test: 10 parallel triggers process one job exactly once
- [ ] The original PDF survives ingestion (asserted by test)
- [ ] All 10 fixtures produce documents passing `validate_consistency`
- [ ] `cursor.service.ts` ≤ 150 LOC; zero desync across a full run on every fixture
- [ ] OMR quality ≥ the `docs/OMR_BASELINE.md` figures

---

## 3. First three tasks

1. **P2-T01** — scaffold `worker/` (FastAPI, `/health`, `/ingest`, Dockerfile from the existing
   OMR image). No pipeline logic; prove the container runs and the harness still passes.
2. **P2-T02** — the `Stage` protocol and content-addressed cache. Everything else builds on it.
3. **P2-T04** — port Recognise with per-page accounting (§2.4). The highest-value single change
   in the phase: it converts a silent data-loss bug into a reported one.
