# DATA_PIPELINE — PiLearn

Design of the ingestion pipeline, the canonical score representation, the analysis stages, and the
data schemas that carry a PDF from upload to a generated learning roadmap.

Companion documents: [`AUDIT_AND_REFACTOR.md`](./AUDIT_AND_REFACTOR.md) ·
[`PRODUCT_SPEC.md`](./PRODUCT_SPEC.md) · [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md)

---

## 1. Pipeline overview

```
                 ┌──────────┐
  PDF / image ──►│ P0 INTAKE│──► raw/{scoreId}/original.{ext}   + SHA-256
  MusicXML ──────│          │    (MusicXML/MIDI skip P1–P3)
  MIDI ──────────└────┬─────┘
                      ▼
      ┌──────────────────────────────┐
      │ P1  RASTERISE                │  pdftoppm -r 300 -png
      │     pdf → page-{n}.png       │  → page count, DPI, dimensions
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P2  RECOGNISE (OMR)          │  engine A: homr   (per page)
      │     png[] → musicxml[]       │  engine B: Audiveris (fallback/arbiter)
      │     + per-page confidence    │  → pageResults[]
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P3  MERGE                    │  relieur concat
      │     musicxml[] → musicxml    │  → continuous measure numbering
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P4  NORMALISE                │  MuseScore3 round-trip
      │     musicxml → musicxml,midi │  xml→mscz→{xml,mid}; metadata injection
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P5  VALIDATE  ◄── GATE       │  structural + musical checks
      │     → confidence report      │  below threshold → REVIEW_REQUIRED
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P6  ENRICH                   │  pianoplayer  → fingering
      │                              │  autoharmonizer → chord symbols
      │                              │  music21 → key, roman numerals, cadences
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P7  BUILD ScoreDocument      │  partitura/music21 → measures, notes, hands
      │                              │  repeat unrolling → playbackOrder
      │                              │  Smith-Waterman → alignment index
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P8  ANALYSE                  │  per-measure difficulty vectors
      │                              │  phrase/cadence segmentation
      │                              │  global grade (syllabus classifier)
      └──────────────┬───────────────┘
                     ▼
      ┌──────────────────────────────┐
      │ P9  PLAN                     │  chunking → stage ladder → LearningPlan
      └──────────────┬───────────────┘
                     ▼
   derived/{scoreId}/{revision}/{document.json, score.musicxml, score.mid,
                                 plan.json, confidence.json, preview/page-*.svg}
```

Every stage is **pure and idempotent** given `(inputHash, stageVersion, params)`. Stage outputs are
content-addressed and cached, so re-running P8 after a difficulty-weight change does not re-run OMR.

---

## 2. Stage specifications

### P0 — Intake

| | |
|---|---|
| Input | multipart upload, ≤ 50 MB |
| Validation | magic-byte type check; PDF page count ≤ 40; image ≥ 150 dpi effective; reject encrypted PDFs |
| Output | `raw/{scoreId}/original.{ext}`, `inputHash = SHA-256(bytes)` |
| Side effects | `ingestion_job` row created (`QUEUED`), queue message enqueued |
| Failure | `400` with a specific reason (`FILE_TOO_LARGE`, `TOO_MANY_PAGES`, `ENCRYPTED_PDF`, `UNSUPPORTED_TYPE`) |

MusicXML / MXL / MIDI inputs skip P1–P3 and enter at P4.

### P1 — Rasterise

```bash
pdftoppm -r 300 -png -aa yes -aaVector yes "$INPUT" "$OUTDIR/page"
```

- Emits `page-01.png … page-NN.png`.
- Records per-page pixel dimensions; flags pages under 2000 px wide as low-resolution (they
  degrade OMR materially and should carry a confidence penalty).
- **Fixes vs legacy:** all paths quoted (legacy `pdf2pack.sh` breaks on the spaces present in
  real filenames); no `sleep 1` synchronisation hacks; a partial rasterise is a hard failure.

### P2 — Recognise (OMR)

**Engine A — homr** (default, per page):

```
homr page-{n}.png → page-{n}.musicxml + logits/confidence
```

**Engine B — Audiveris 5.x** (grand-staff arbiter):

```
audiveris -batch -export -output {dir} page-{n}.png → page-{n}.mxl
```

Selection policy:

```
run A on all pages
if  A.documentConfidence ≥ 0.85            → accept A
elif A fails structurally on ≥ 20 % pages  → run B, accept the better validated result
else                                        → run B, arbitrate per page by page validation score
```

**Per-page confidence** (0–1), computed without a ground truth:

| Signal | Weight |
|---|---|
| Engine's own token/symbol confidence (mean over recognised symbols) | 0.30 |
| Staff-count consistency (a piano page should yield 2 staves per system) | 0.20 |
| Measure duration validity (Σ note durations == time-signature capacity per measure) | 0.25 |
| Key-signature stability across the page | 0.10 |
| Pitch-range plausibility (A0–C8, no isolated extreme outliers) | 0.10 |
| Voice-leading sanity (no > 2-octave leaps inside a voice without a rest) | 0.05 |

**Critical fix vs legacy:** `pdf2pack.sh` only fails when *every* page fails, silently dropping
failed pages from the merge — a 12-page piece can become a 9-page score with no warning. New rule:
**any dropped page marks the document `REVIEW_REQUIRED` and records which page and why.**

### P3 — Merge

`relieur concat page-*.musicxml -o merged.musicxml`

Post-conditions asserted:
- measure numbers are strictly increasing and gap-free,
- part count is constant across page boundaries,
- key/time signature at a page seam either matches the previous page or carries an explicit change.

Violations → `REVIEW_REQUIRED` with the offending seam identified.

### P4 — Normalise

MuseScore 3 round-trip, which repairs malformed OMR MusicXML and produces a coherent MIDI:

```
musescore3 -f -o work.mscz     merged.musicxml
musescore3 -f -o score.musicxml work.mscz
musescore3 -f -o score.mid      work.mscz
```

Then inject metadata (title, composer, source) into `<work>`/`<identification>`.

**Fix vs legacy:** the current scripts round-trip through MIDI *before* normalising
(`musicxml → mid → musicxml`), which destroys voicing, ties, and spelling. Round-trip through
`.mscz` only; MIDI is a terminal export, never an intermediate.

### P5 — Validate (the confidence gate)

| Check | Severity |
|---|---|
| Every measure's summed duration equals the time-signature capacity | error |
| Exactly 2 staves for solo piano (or an explicit `partCount` acknowledgement) | error |
| No measure with zero notes and zero rests | error |
| Tempo present or inferable | warning |
| Key signature stable or with explicit changes | warning |
| No page dropped in P2 | error |
| ≥ 1 note assigned to each hand across the document | error |
| Pitch range within A0–C8 | warning |

```
documentConfidence = mean(pageConfidence) × (1 - 0.15 × errorCount) - 0.03 × warningCount
```

| Band | Action |
|---|---|
| `≥ 0.85` | proceed automatically |
| `0.60 – 0.85` | proceed, but mark `REVIEW_SUGGESTED` and surface the heat strip |
| `< 0.60` | halt at `REVIEW_REQUIRED`; the user must accept, correct, re-run, or upload MusicXML |

### P6 — Enrich

| Step | Tool | Output |
|---|---|---|
| Fingering | `pianoplayer score.musicxml -o score.musicxml -z` | `<fingering>` elements, extracted to `fingering[]` |
| Harmony | `autoharmonizer` (only when no `<harmony>` present) | chord symbols per measure/beat |
| Key & function | `music21` (`analyze('key')`, `roman.romanNumeralFromChord`) | global key, per-measure roman numerals |
| Cadences | `music21` chordify + cadence heuristics | cadence points → phrase boundaries |

Gate: run `has_fingering.py` / `has_harmony.py` equivalents first — never overwrite fingering or
harmony that the source score already carries.

### P7 — Build ScoreDocument

This is where the client-side `cursor.service.ts` logic moves server-side.

1. **Parse** `score.musicxml` with `partitura` (exact MusicXML→array with divisions preserved),
   cross-checked against `music21` for harmonic/structural annotations.
2. **Hand assignment.** Staff 1 → right, staff 2 → left by default. For cross-staff and single-staff
   passages, apply the ported chord-splitting heuristic from `hand-detector.service.ts`
   (onset grouping → candidate split points → penalty over hand span, voice continuity and
   previous split position).
3. **Unroll playback order.** Port `cursor.service.buildOsmdMeasureSequence()`: repeats, voltas,
   D.C., D.S., segno, coda, fine. **Replace the `security < 10000` and `MAX_DACAPO` magic guards
   with explicit structural validation** — an unresolvable repeat structure is a reported error,
   not a silent truncation.
4. **Build the alignment index.** Align notated events (MusicXML) against MIDI events using the
   ported Smith-Waterman (`sw2` variant), producing one `TimelineStep` per notation position with
   its MIDI ticks, seconds and pitch set.
5. **Emit** `ScoreDocument` (§6.1) and the compact `AlignmentIndex` (§6.2).

The client then never computes alignment: it fetches the index and does an `O(1)` lookup per
cursor advance.

### P8 — Analyse

For each measure, compute the ten features in `PRODUCT_SPEC.md` §5.4 and the scalar difficulty.
Then:

- **Segment** into phrases at cadence points, long rests, double barlines, texture changes, and
  repeat boundaries → `Segment[]`.
- **Grade** the document globally with `piano-syllabus-classifier` (0–8), retained from the legacy
  pipeline, and record it alongside the per-measure vectors.

Every analysis output carries `analysisVersion`. Bumping the version invalidates derived plans for
*regeneration offers* — never for silent rewrites.

### P9 — Plan

Apply the chunking algorithm (`PRODUCT_SPEC.md` §5.1) and the stage ladder (§5.2) to produce a
`LearningPlan` (§6.4). Plans are immutable and versioned; adaptation appends a new version with a
`derivedFrom` pointer and a reason.

---

## 3. Storage layout

```
r2://pilearn-media/
  raw/{scoreId}/original.pdf                       # never mutated
  raw/{scoreId}/original.sha256
  derived/{scoreId}/{revision}/
      document.json          # ScoreDocument (gzip)
      index.json            # AlignmentIndex (gzip, hot path)
      score.musicxml        # normalised, fingered, harmonised
      score.mid             # normalised MIDI
      confidence.json       # ConfidenceReport
      plan.json             # default LearningPlan (per-user plans live in Postgres)
      preview/page-{n}.svg  # engraved preview for the review UI
      pages/page-{n}.png    # rasterised source, retained only while REVIEW_REQUIRED
```

`revision` increments on any re-ingestion or re-analysis. Old revisions are retained so an in-flight
learner's plan keeps resolving against the document it was built from.

**Change from legacy:** the current pipeline packs `{pdf, midi, musicxml, metadata.json}` into a
single ZIP at one bucket key, overwriting the *upload* key with the *result* — the original PDF is
destroyed by the very job that consumes it (`WorkloadProcessingService` puts the result at the same
`makeBucketKeyFromScore(score)` key it read the input from). Raw and derived must be separate
prefixes, and raw must be immutable.

---

## 4. Client-side representation

| Purpose | Format | Source |
|---|---|---|
| Engraving | MusicXML string → OSMD `load()` | `score.musicxml` |
| Playback | `@tonejs/midi` `Midi` object | `score.mid` |
| Cursor tracking | `AlignmentIndex` | `index.json` |
| Practice logic | `ScoreDocument.measures[]` + `LearningPlan` | `document.json`, `/plan` |
| Falling notes | `ScoreDocument.timeline[]` (pitch, startSec, durSec, hand) | `document.json` |

The client fetches `index.json` (small) eagerly and `document.json` lazily — only the practice and
progress surfaces need the full document.

---

## 5. Job orchestration

Replaces the broken Cloud Run Job path (`AUDIT_AND_REFACTOR.md` §R2).

```
POST /score/{id}/ingestion
  └─► INSERT ingestion_job (status=QUEUED, idempotency_key=inputHash+params)
  └─► Cloud Tasks enqueue { jobId }        one message per job
          │
          ▼
      Worker (Cloud Run service) POST /ingest { jobId }
          │
          ├─ atomic claim:
          │     UPDATE ingestion_job SET status='RUNNING', lease_until=now()+15min,
          │            attempt=attempt+1
          │     WHERE id=:jobId AND status IN ('QUEUED','LEASE_EXPIRED')
          │     RETURNING *              -- 0 rows ⇒ already claimed ⇒ ack and exit
          │
          ├─ run P1…P9, emitting stage events (persisted + fanned out over SSE)
          │
          └─ terminal: COMPLETED | REVIEW_REQUIRED | FAILED(reason, stage)
                 retryable failure & attempt < 3  → release to QUEUED with backoff
                 otherwise                        → FAILED, dead-letter, notify owner
```

Rules:

- **One message = one job.** Never drain a table.
- **Idempotency key** = `SHA-256(inputHash ‖ engineVersions ‖ analysisVersion)`. Re-submitting the
  same file with the same versions returns the existing job.
- **Lease + sweeper.** A cron marks `RUNNING` jobs past `lease_until` as `LEASE_EXPIRED` so they can
  be reclaimed. No orphaned jobs.
- **No `System.exit`.** The worker is a long-lived HTTP service that scales to zero.
- **Bounded concurrency** per worker instance (OMR is CPU- and memory-heavy: torch + MuseScore).

Progress events (persisted to `ingestion_event`, streamed over SSE):

```json
{ "jobId": "...", "seq": 7, "stage": "RECOGNISE", "progress": 0.42,
  "message": "recognising page 5/12", "at": "2026-08-16T10:31:02Z" }
```

---

## 6. Data schemas

TypeScript is used as the schema notation; these types are generated from `openapi/api.yaml` for
the client and mapped to JSONB columns + Java records on the server.

### 6.1 ScoreDocument

```ts
interface ScoreDocument {
  scoreId: string;                 // uuid
  revision: number;
  schemaVersion: '1.0';
  analysisVersion: string;         // e.g. "analysis-2026.08"

  source: {
    kind: 'PDF' | 'IMAGE' | 'MUSICXML' | 'MIDI';
    inputHash: string;             // sha-256
    pageCount: number | null;
    omrEngine: 'homr' | 'audiveris' | 'none';
    omrEngineVersion: string | null;
  };

  meta: {
    title: string;
    composer: string;
    arranger: string | null;
    key: { tonic: string; mode: 'major' | 'minor'; fifths: number };
    timeSignatures: Array<{ measure: number; numerator: number; denominator: number }>;
    tempoMap: Array<{ measure: number; beat: number; bpm: number }>;
    targetTempoBpm: number;        // authoritative practice target
    measureCount: number;
    durationSec: number;
    divisions: number;             // MusicXML divisions per quarter
    ppq: number;                   // MIDI ticks per quarter
    hasPickup: boolean;
  };

  parts: Array<{
    id: string;
    staffCount: number;
    handMapping: Record<number, 'RIGHT' | 'LEFT'>;   // staff index → hand
  }>;

  measures: Measure[];
  segments: Segment[];
  playbackOrder: number[];         // measure indices in performance order (repeats unrolled)
  timeline: TimelineStep[];
  harmony: HarmonyEntry[];         // retained from the legacy schema
  difficulty: DifficultySummary;
  confidence: ConfidenceReport;
}
```

```ts
interface Measure {
  index: number;                   // 0-based, notation order
  number: string;                  // printed number ("1", "12a")
  startTick: number;
  endTick: number;
  startSec: number;
  endSec: number;
  timeSignature: { numerator: number; denominator: number };
  keyFifths: number;
  tempoBpm: number;
  isPickup: boolean;
  repeat: {
    startsRepeat: boolean;
    endsRepeat: boolean;
    volta: number | null;
    jump: 'DC' | 'DS' | 'CODA' | 'FINE' | null;
  };
  notes: ScoreNote[];
  difficulty: MeasureDifficulty;
  segmentId: string;
}

interface ScoreNote {
  id: string;                      // stable: `${measureIndex}:${voice}:${startTick}:${midi}`
  midi: number;                    // 21..108
  pitch: string;                   // "C#4" (spelled, matches notation)
  startTick: number;
  durationTicks: number;
  startSec: number;
  durationSec: number;
  hand: 'RIGHT' | 'LEFT';
  staff: number;
  voice: number;
  finger: number | null;           // 1..5
  tiedFrom: string | null;
  tiedTo: string | null;
  isGrace: boolean;
  isChordMember: boolean;
  articulations: string[];         // "staccato", "accent", ...
  dynamic: string | null;          // "mf", ...
}
```

```ts
interface Segment {                 // a phrase / musical unit
  id: string;
  startMeasure: number;
  endMeasure: number;
  kind: 'PHRASE' | 'SECTION' | 'REPEAT_BLOCK';
  boundaryReason: 'CADENCE' | 'REST' | 'DOUBLE_BARLINE' | 'TEXTURE_CHANGE' | 'REPEAT' | 'END';
  cadence: 'PERFECT' | 'IMPERFECT' | 'HALF' | 'DECEPTIVE' | 'PLAGAL' | null;
  confidence: number;               // 0..1
}
```

```ts
interface MeasureDifficulty {
  noteDensity: number;              // all features normalised 0..1
  minIOI: number;
  maxSpan: number;
  polyphony: number;
  handIndependence: number;
  accidentalRate: number;
  leapSize: number;
  rhythmComplexity: number;
  positionShifts: number;
  ornamentCount: number;
  score: number;                    // 0..10
  patterns: Array<'SCALE_RUN' | 'ARPEGGIO' | 'BROKEN_CHORD' | 'OCTAVE_LEAP'
                 | 'TRILL' | 'CROSS_HAND' | 'SYNCOPATION' | 'POLYRHYTHM'>;
}

interface DifficultySummary {
  globalGrade: number;              // 0..8, piano-syllabus-classifier
  meanMeasureDifficulty: number;    // 0..10
  p90MeasureDifficulty: number;
  hardestMeasures: number[];        // top 10 measure indices
  weightsVersion: string;
}
```

### 6.2 AlignmentIndex (hot path — keep small)

```ts
interface AlignmentIndex {
  scoreId: string;
  revision: number;
  ppq: number;
  steps: TimelineStep[];
  byTick: Record<number, number>;   // midi tick → step index
  byMeasure: Record<number, number>;// measure index → first step index
}

interface TimelineStep {
  index: number;                    // position in performance order
  measureIndex: number;             // notation measure
  osmdCursorIndex: number;          // OSMD cursor iterator position
  startTick: number;
  startSec: number;
  durationTicks: number;
  pitches: number[];                // expected MIDI pitches at this step
  hands: Array<'RIGHT' | 'LEFT'>;   // parallel to pitches
  isRepeatJump: boolean;
  jumpTargetIndex: number | null;
  alignmentConfidence: number;      // 0..1 from Smith-Waterman
}
```

This replaces `OsmdArrayElement` from `desktop/model/model.ts`. Differences that matter: it is
computed once server-side, it is complete (no `null` pitch fields to defend against), and every
step carries an explicit alignment confidence instead of a global pass/fail from `verify()`.

### 6.3 ConfidenceReport

```ts
interface ConfidenceReport {
  documentConfidence: number;                       // 0..1
  status: 'OK' | 'REVIEW_SUGGESTED' | 'REVIEW_REQUIRED';
  pages: Array<{
    page: number;
    engine: 'homr' | 'audiveris';
    confidence: number;
    recognised: boolean;
    reason: string | null;
  }>;
  measures: Array<{ measure: number; confidence: number; issues: string[] }>;
  issues: Array<{
    code: 'MEASURE_DURATION_MISMATCH' | 'STAFF_COUNT_ANOMALY' | 'PAGE_DROPPED'
        | 'KEY_INSTABILITY' | 'PITCH_OUT_OF_RANGE' | 'UNRESOLVED_REPEAT'
        | 'NO_TEMPO' | 'HAND_ASSIGNMENT_AMBIGUOUS';
    severity: 'ERROR' | 'WARNING';
    measure: number | null;
    page: number | null;
    detail: string;
  }>;
}
```

### 6.4 LearningPlan

```ts
interface LearningPlan {
  id: string;
  scoreId: string;
  scoreRevision: number;
  userId: string;
  version: number;
  derivedFrom: string | null;       // previous plan id when adapted
  adaptationReason: string | null;
  createdAt: string;
  generatorVersion: string;

  params: {
    goalTempoPct: number;           // 1.0 = printed tempo
    handsSeparateFirst: boolean;
    weeklyMinutes: number;
    learnerLevel: number;           // 0..8, self-declared or inferred
  };

  estimate: { totalStages: number; estimatedWeeks: number; estimatedMinutesPerWeek: number };
  chunks: Chunk[];
  stages: Stage[];                  // flat, ordered; chunkId references above
}

interface Chunk {
  id: string;
  ordinal: number;
  startMeasure: number;             // inclusive, notation index
  endMeasure: number;               // inclusive
  segmentIds: string[];
  difficulty: number;               // 0..10, aggregate
  kind: 'PRIMARY' | 'JOIN' | 'MICRO' | 'REVIEW';
  joinedChunkIds: string[] | null;  // for kind === 'JOIN'
  label: string;                    // "Bars 1-8 — opening phrase"
}

interface Stage {
  id: string;
  chunkId: string;
  ordinal: number;                  // global order in the plan
  handMode: 'RIGHT' | 'LEFT' | 'BOTH';
  tempoPct: number;                 // of meta.targetTempoBpm
  tempoBpm: number;                 // resolved, for display
  mode: 'WAIT' | 'FLOW';
  useMetronome: boolean;
  criterion: MasteryCriterion;
  status: 'LOCKED' | 'AVAILABLE' | 'IN_PROGRESS' | 'PASSED' | 'SKIPPED';
  userEdited: boolean;
  estimatedMinutes: number;
}

interface MasteryCriterion {
  minPitchAccuracy: number;         // 0..1
  maxTimingRmsMs: number;
  consecutiveCleanRuns: number;
  maxErrorsPerMeasure: number;
  maxRushBiasMs: number;            // |signed mean deviation|
}
```

### 6.5 Practice telemetry

```ts
interface PracticeSession {
  id: string;
  userId: string;
  scoreId: string;
  planId: string;
  startedAt: string;
  endedAt: string | null;
  totalPlayMs: number;
  attemptCount: number;
  device: { midiInput: string | null; latencyOffsetMs: number };
}

interface Attempt {
  id: string;
  sessionId: string;
  stageId: string;
  chunkId: string;
  index: number;                    // attempt number within the stage
  startedAt: string;
  durationMs: number;
  tempoBpm: number;
  handMode: 'RIGHT' | 'LEFT' | 'BOTH';
  mode: 'WAIT' | 'FLOW';

  result: {
    verdict: 'PASS' | 'RETRY' | 'STEP_DOWN' | 'ABANDONED';
    pitchAccuracy: number;          // correct / expected
    notesExpected: number;
    notesCorrect: number;
    notesWrong: number;
    notesMissed: number;
    notesExtra: number;
    timingMeanMs: number;           // signed: negative = rushing
    timingRmsMs: number;
    rushBiasMs: number;
    completionPct: number;          // how far into the chunk before abandon
  };

  measureResults: MeasureResult[];
  noteEvents?: NoteEvent[];         // optional raw capture, for replay (F4.13)
}

interface MeasureResult {
  measureIndex: number;
  notesExpected: number;
  notesCorrect: number;
  notesWrong: number;
  notesMissed: number;
  timingRmsMs: number;
  errorRate: number;                // 0..1
}

interface NoteEvent {
  t: number;                        // ms from attempt start
  midi: number;
  velocity: number;
  type: 'ON' | 'OFF';
  expectedStepIndex: number | null; // resolved against AlignmentIndex
  deviationMs: number | null;
  classification: 'CORRECT' | 'EARLY' | 'LATE' | 'WRONG' | 'EXTRA';
}
```

### 6.6 Progress

```ts
interface ProgressState {
  userId: string;
  scoreId: string;
  planId: string;
  updatedAt: string;
  overallPct: number;               // passed stages / total
  currentStageId: string | null;
  totalPracticeMs: number;
  sessionCount: number;
  chunkMastery: ChunkMastery[];
  tempoCurve: Array<{ at: string; chunkId: string; tempoBpm: number }>;
  weakestMeasures: Array<{ measureIndex: number; errorRate: number; attempts: number }>;
}

interface ChunkMastery {
  chunkId: string;
  state: 'NOT_STARTED' | 'LEARNING' | 'CONSOLIDATING' | 'MASTERED' | 'NEEDS_REVIEW';
  bestTempoBpm: number;
  bestAccuracy: number;
  attempts: number;
  lastPracticedAt: string | null;
  nextReviewAt: string | null;      // spaced repetition (F3.7)
}
```

---

## 7. Persistence model

New tables (Liquibase changesets `021+`, schema `pianoml`):

| Table | Key columns | Notes |
|---|---|---|
| `score_document` | `score_id`, `revision`, `schema_version`, `analysis_version`, `document jsonb`, `index jsonb`, `confidence jsonb`, `created_at` | PK `(score_id, revision)`; GIN index on `document` for measure queries |
| `ingestion_job` | `id`, `score_id`, `status`, `stage`, `attempt`, `lease_until`, `idempotency_key` (unique), `error_code`, `error_detail`, `created_at`, `updated_at` | Partial index on `status IN ('QUEUED','LEASE_EXPIRED')` |
| `ingestion_event` | `job_id`, `seq`, `stage`, `progress`, `message`, `at` | Append-only; SSE replay source |
| `learning_plan` | `id`, `score_id`, `score_revision`, `user_id`, `version`, `derived_from`, `params jsonb`, `plan jsonb`, `created_at` | Unique `(user_id, score_id, version)` |
| `practice_session` | `id`, `user_id`, `score_id`, `plan_id`, `started_at`, `ended_at`, `total_play_ms` | |
| `attempt` | `id`, `session_id`, `stage_id`, `chunk_id`, `index`, `tempo_bpm`, `hand_mode`, `result jsonb`, `started_at` | Index `(session_id, started_at)` |
| `measure_result` | `attempt_id`, `measure_index`, `notes_expected`, `notes_correct`, `notes_wrong`, `notes_missed`, `timing_rms_ms` | Relational, not JSONB — this is the aggregation hot path for the weakest-bars heat map |
| `chunk_mastery` | `user_id`, `score_id`, `chunk_id`, `state`, `best_tempo_bpm`, `best_accuracy`, `attempts`, `last_practiced_at`, `next_review_at` | PK `(user_id, score_id, chunk_id)` |

**JSONB vs relational rule:** documents and plans are read whole and rarely queried by field →
JSONB. `measure_result` is aggregated across attempts → relational columns.

Changes to existing tables:

```sql
ALTER TABLE pianoml.score ADD COLUMN rights VARCHAR(32) NOT NULL DEFAULT 'USER_UPLOAD_PRIVATE';
ALTER TABLE pianoml.score ALTER COLUMN public_domain SET DEFAULT false;   -- see AUDIT §R5
ALTER TABLE pianoml.score ADD COLUMN current_revision INT;
ALTER TABLE pianoml.score ADD COLUMN document_confidence REAL;
```

`workload` is superseded by `ingestion_job`; migrate rows and retire the table after Phase 2.

---

## 8. Client integration points

### 8.1 Score load

```ts
// ScoreStore.load(scoreId)
const [index, musicXml, midiBytes] = await Promise.all([
  api.getDocumentIndex(scoreId),      // index.json      — small, blocking
  api.getScoreFile(scoreId, 'musicxml'),
  api.getScoreFile(scoreId, 'midi'),
]);
osmd.load(musicXml);                   // engraving
const midi = new Midi(midiBytes);      // playback
scoreIndex.set(index);                 // cursor tracking — no client-side alignment
// document.json is fetched lazily by the practice/progress surfaces
```

### 8.2 Cursor advance (replaces `cursor.service.nextNote`)

```ts
advance(tick: number): void {
  const stepIdx = this.index().byTick[tick];
  if (stepIdx === undefined) return;
  const step = this.index().steps[stepIdx];
  this.osmd.cursor.moveToIndex(step.osmdCursorIndex);   // O(1) lookup, no search
  this.transport.setMeasure(step.measureIndex);
}
```

### 8.3 Attempt submission

Attempts are buffered client-side during a run and POSTed on completion. On network failure they
queue in IndexedDB and flush on reconnect (F6.5). `noteEvents` are sent only when replay is
enabled — a 5-minute run at moderate density is roughly 2 000 events (~80 KB gzipped).

---

## 9. Migration of existing scores

The library holds scores already ingested with the legacy pipeline (ZIP of pdf/midi/musicxml/metadata).

```
For each score with has_files = true:
  1. Fetch the legacy ZIP from R2.
  2. Extract score.musicxml + score.mid → write to derived/{scoreId}/1/.
  3. If the original PDF is present in the ZIP → copy to raw/{scoreId}/original.pdf.
     (Legacy overwrote the upload key with the result, so many scores have no original —
      those enter at P4, treated as a MusicXML source.)
  4. Run P5 → P9 only (no OMR re-run): validate, enrich if missing, build the document,
     analyse, generate a default plan.
  5. Set score.current_revision = 1, score.document_confidence, score.rights.
```

Backfill runs as a batch worker job over the queue, rate-limited, resumable, with a dry-run mode
that reports how many legacy scores fail P5 validation before anything is written.

---

## 10. Testing strategy for the pipeline

| Level | What | How |
|---|---|---|
| Golden corpus | The 10 fixture PDFs (moved to a private bucket) | Snapshot `document.json` measure count, note count, key, time signatures, `documentConfidence`. Any drift fails CI. |
| Stage units | P1–P9 individually | Pure-function tests with fixture inputs; each stage has ≥ 5 cases including one malformed input |
| Alignment | Ported Smith-Waterman | Property tests: identity alignment on a MusicXML-derived MIDI must yield 1:1 steps with confidence 1.0 |
| Repeat unrolling | `playbackOrder` | Hand-built MusicXML fixtures: simple repeat, 1st/2nd volta, D.C. al Fine, D.S. al Coda, nested repeats |
| Difficulty | Feature extraction | Fixed vectors on synthetic measures (single note, dense chord run, cross-hand, polyrhythm) |
| Chunking | `LearningPlan` generation | Assert no chunk crosses a phrase boundary; assert size bounds; assert hard-bar isolation |
| Confidence | Gate behaviour | Inject a dropped page, a duration mismatch, a staff anomaly — assert the correct `status` and issue codes |
| Contract | OpenAPI | Schema-validate every response against `api.yaml` in integration tests |
| End-to-end | Upload → plan | Playwright: upload a fixture PDF, wait on the SSE stream, assert a plan renders with the expected stage count |

**Coverage floor: 80 % on `ingestion`, `learning`, alignment and difficulty modules; 100 % of the
fixture corpus exercised on every CI run.**
