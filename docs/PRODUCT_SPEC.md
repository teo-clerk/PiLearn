# PRODUCT_SPEC — PiLearn

Target product specification for the pivot from **PianoML** (free-play practice tool) to
**PiLearn** (guided, adaptive piano-learning assistant driven by an uploaded sheet-music PDF).

Companion documents: [`AUDIT_AND_REFACTOR.md`](./AUDIT_AND_REFACTOR.md) ·
[`DATA_PIPELINE.md`](./DATA_PIPELINE.md) · [`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md)

---

## 1. Product thesis

> A pianist has a PDF of a piece they want to learn. Today they open it, play it badly from bar 1,
> get stuck at bar 34, and lose momentum. PiLearn turns that PDF into a **plan**: what to practise,
> in what order, at what tempo, with which hand, until which criterion is met — and it watches you
> play and adapts.

Three things must be true for the product to work:

1. **Ingestion must be trustworthy.** A learning plan built on a mis-recognised score is worse than
   no plan. Confidence must be measured and surfaced, and correction must be possible.
2. **The plan must be musically credible.** Chunk boundaries at phrase/cadence points, not every
   4 bars. Difficulty from real features, not note count.
3. **Feedback must be honest and immediate.** Note-level timing and accuracy, attributed to a
   specific measure, aggregated into a mastery signal the learner can see moving.

### Non-goals for v1

- Audio (microphone) input for note detection — MIDI keyboard only.
- Sight-reading trainer, ear training, theory course.
- Social features, leaderboards, teacher dashboards.
- Mobile-native apps (responsive web only).
- Automatic arrangement/simplification of a score.

---

## 2. Users

| Persona | Level | Core need | Success signal |
|---|---|---|---|
| **Returning adult learner** (primary) | RCM 3–6 | "I have this PDF, tell me how to actually learn it" | Completes a piece end-to-end in 4–8 weeks |
| **Self-taught intermediate** | RCM 5–8 | "I can play it badly; I need to fix bars 30–45" | Targeted chunk mastery, measured tempo growth |
| **Student with a teacher** | any | "Practise correctly between lessons" | Exportable practice log |
| **Curator/owner** (existing) | — | Manage catalog, authors, genres | Retained from PianoML |

Assumed hardware: a MIDI keyboard (USB/BLE) and a Chromium-family browser (WebMIDI). Degraded mode
without MIDI: playback, follow-along cursor, and stage navigation — no scoring.

---

## 3. Core user flows

### 3.1 Flow A — Upload → Process → Plan (the hero flow)

```
1. UPLOAD
   User drops a PDF (or MusicXML / MIDI / image). Enters title + composer (or accepts
   MusicBrainz autofill). Chooses: generate fingering? split hands?
      → POST /score            (create the score record)
      → POST /score/{id}/pdf/{version}/{revision}   (upload bytes, enqueue ingestion)
      → 202 Accepted { scoreId, jobId }

2. PROCESS  (async, 30 s – 5 min depending on page count)
   Live job status via SSE. Stages shown to the user:
      rasterising → recognising (page 3/12) → merging → normalising →
      analysing → fingering → building plan
      → GET /score/{id}/ingestion/events   (SSE stream)

3. REVIEW  (gate — only if confidence < threshold)
   Side-by-side: original PDF page vs engraved result. Per-measure confidence heat strip.
   User can: accept · flag a measure as wrong · re-run with the other OMR engine ·
   upload a MusicXML instead.
      → POST /score/{id}/ingestion/review

4. PLAN
   Generated roadmap presented as stages:
      "Interstellar Theme — 64 bars, Grade 5, est. 6 weeks"
      Stage 1  Bars 1–8   · Right hand  · 60 bpm (50 % target)
      Stage 2  Bars 1–8   · Left hand   · 60 bpm
      Stage 3  Bars 1–8   · Both hands  · 60 → 90 bpm
      Stage 4  Bars 9–16  · ...
      ...
      Stage N  Bars 1–64  · Both hands  · 100 % tempo · performance run
   User can reorder, skip, or re-scope stages; edits persist.
      → GET  /score/{id}/plan
      → POST /score/{id}/plan/regenerate  { goalTempoPct, weeklyMinutes, handsSeparateFirst }

5. PRACTISE
   Enter a stage → the practice surface (§3.2).

6. PROGRESS
   Per-stage mastery, tempo curve, weakest-bars heat map, streak, next-session suggestion.
```

### 3.2 Flow B — A practice session

```
ENTER STAGE
  Score viewport scrolls to the chunk; out-of-chunk bars dimmed.
  HUD: stage goal ("Bars 1-8, right hand, 3 clean runs at 60 bpm"), progress 1/3.

COUNT-IN → PLAY
  Metronome count-in. Cursor advances with the transport.
  Wait-mode (default for stages 1-3 of a chunk): the transport blocks until the
  expected notes are played — this is the existing `shouldPause` mechanism.
  Flow-mode (later stages): the transport does not wait; errors are recorded.

LIVE FEEDBACK
  Note-head colouring: correct / early / late / wrong-pitch / missed.
  Virtual keyboard highlights expected vs played.
  Rushing/dragging meter: signed rolling mean of timing deviation.

END OF RUN
  Run scored: accuracy %, timing RMS (ms), rushing bias, per-measure error counts.
  Verdict against the stage criterion:
     PASS  → progress 2/3, or advance stage
     RETRY → same chunk
     STEP DOWN → tempo -10 %, or split the chunk, or go hands-separate

END OF SESSION
  Summary + updated plan + "next time, start here".
```

### 3.3 Flow C — Library (retained)

Browse by author / genre / key / grade / track count, score detail with YouTube references,
account-owned scores, MusicBrainz enrichment. Carried over from PianoML largely unchanged; the
difference is that every score now also has a **plan** and a **progress** state.

---

## 4. Feature specification

### F1 — Ingestion

| ID | Feature | Priority |
|---|---|---|
| F1.1 | PDF upload (≤ 50 MB, ≤ 40 pages), drag-drop, client-side page-count + type validation | P0 |
| F1.2 | MusicXML / MXL / MIDI upload bypassing OMR | P0 |
| F1.3 | Image (PNG/JPG) upload | P1 |
| F1.4 | Async ingestion with live per-stage progress (SSE) | P0 |
| F1.5 | Per-measure OMR confidence, aggregated into a document-level score | P0 |
| F1.6 | Review gate below a confidence threshold, with PDF↔engraving side-by-side | P1 |
| F1.7 | Second OMR engine (Audiveris) as fallback/arbiter for grand-staff piano | P1 |
| F1.8 | Manual measure correction (fix pitch/duration/hand of a recognised note) | P2 |
| F1.9 | Automatic fingering generation (pianoplayer) | P0 (exists) |
| F1.10 | Automatic harmonic analysis (chord symbols per measure/beat) | P1 (exists) |

### F2 — Score analysis

| ID | Feature | Priority |
|---|---|---|
| F2.1 | Normalised `ScoreDocument`: measures, clefs, key/time signatures, tempo map, hands | P0 |
| F2.2 | Resolved playback order (repeats, voltas, D.C./D.S., codas) | P0 |
| F2.3 | Precomputed notation↔MIDI alignment index (replaces client-side `cursor.service`) | P0 |
| F2.4 | Per-measure difficulty vector + scalar 0–10 | P0 |
| F2.5 | Global grade (RCM-like 0–8) via `piano-syllabus-classifier` | P0 (exists) |
| F2.6 | Phrase/cadence segmentation for musically sensible chunk boundaries | P1 |
| F2.7 | Hand-independence and polyphony metrics per measure | P1 |
| F2.8 | Technical-pattern detection (scale run, arpeggio, broken chord, trill, octave leap) | P2 |

### F3 — Learning pathway generator

| ID | Feature | Priority |
|---|---|---|
| F3.1 | Chunking into practice units, phrase-aware, difficulty-balanced | P0 |
| F3.2 | Hands-separate → hands-together stage progression per chunk | P0 |
| F3.3 | Tempo ramp: start tempo derived from difficulty; ladder to target | P0 |
| F3.4 | Mastery criteria per stage (accuracy, timing RMS, consecutive clean runs) | P0 |
| F3.5 | Chunk merging: adjacent mastered chunks combine into a "joining" stage | P0 |
| F3.6 | Adaptive re-planning from actual performance (split hard chunks, drop tempo) | P1 |
| F3.7 | Spaced review of previously mastered chunks | P1 |
| F3.8 | Session budget planning ("I have 20 minutes today") | P1 |
| F3.9 | Auto-generated warm-up from the piece's key/technical patterns (reuses `exercises/`) | P2 |
| F3.10 | User-editable plan (reorder, skip, re-scope, pin) | P1 |

### F4 — Interactive practice

| ID | Feature | Priority |
|---|---|---|
| F4.1 | Sheet view (OSMD) with stage-scoped highlight and dimmed context | P0 |
| F4.2 | Cursor tracking with auto-scroll | P0 (exists, needs hardening) |
| F4.3 | Wait-for-hand mode (transport blocks until correct notes played) | P0 (exists) |
| F4.4 | Virtual keyboard with expected/played highlighting | P0 (exists) |
| F4.5 | Metronome + count-in | P0 (exists) |
| F4.6 | Tempo control with stage-driven default | P0 (exists) |
| F4.7 | Loop over the current chunk | P0 (exists) |
| F4.8 | Note-level live feedback colouring (correct/early/late/wrong/missed) | P0 (partial) |
| F4.9 | Rushing/dragging meter | P1 |
| F4.10 | **Falling-notes view** (piano-roll) as an alternative to sheet view | P1 |
| F4.11 | Run summary + per-measure error heat map | P0 |
| F4.12 | Input latency calibration wizard | P1 |
| F4.13 | Record & replay a run | P2 |

### F5 — Progress

| ID | Feature | Priority |
|---|---|---|
| F5.1 | Attempt persistence (per run: stage, tempo, accuracy, timing, per-measure errors) | P0 |
| F5.2 | Per-chunk mastery state machine | P0 |
| F5.3 | Piece dashboard: stage completion, tempo curve, weakest bars | P0 |
| F5.4 | Practice streak + time-on-task | P1 |
| F5.5 | Exportable practice log (CSV/PDF) for teachers | P2 |

### F6 — Platform / cross-cutting

| ID | Feature | Priority |
|---|---|---|
| F6.1 | Accounts, JWT auth, ownership (exists — secret handling must be fixed) | P0 |
| F6.2 | Private-by-default user uploads with an explicit rights field | P0 |
| F6.3 | Responsive layout: desktop-first practice surface, tablet-usable | P0 |
| F6.4 | WCAG 2.2 AA: keyboard operability, contrast, reduced-motion, screen-reader labels | P0 |
| F6.5 | Offline-tolerant practice session (queue attempts, sync later) | P2 |
| F6.6 | i18n scaffolding (codebase currently mixes FR/EN) | P1 |

---

## 5. The learning model

This is the conceptual core of the pivot. Full schemas in [`DATA_PIPELINE.md`](./DATA_PIPELINE.md) §6.

```
ScoreDocument            the normalised, analysed piece (immutable per ingestion revision)
  └─ Measure[]           notes, hands, key/time sig, difficulty vector
       └─ Segment[]      phrase-level groupings from cadence/rest analysis

LearningPlan             generated from a ScoreDocument + learner profile
  └─ Chunk[]             a contiguous measure range that is practised as a unit
       └─ Stage[]        an ordered practice objective over that chunk
            ├─ handMode  RIGHT | LEFT | BOTH
            ├─ tempoPct  fraction of target tempo
            ├─ mode      WAIT | FLOW
            └─ criterion mastery test

PracticeSession          one sitting
  └─ Attempt[]           one run of one stage
       └─ MeasureResult[]  per-measure accuracy/timing/errors

ProgressState            derived, per (user, score)
  └─ ChunkMastery[]      NOT_STARTED | LEARNING | CONSOLIDATING | MASTERED | NEEDS_REVIEW
```

### 5.1 Chunking algorithm (F3.1)

Inputs: `Measure[]` with difficulty vectors, `Segment[]` phrase boundaries, learner level.

```
1. Candidate boundaries = phrase boundaries ∪ section boundaries ∪ repeat boundaries.
2. Target chunk size = clamp(round(12 / mean_difficulty), 2, 8) measures.
3. Greedily accumulate measures until either:
     - the chunk reaches target size, or
     - a strong candidate boundary is hit within ±2 measures of target.
4. Split any chunk whose peak measure difficulty > 2× the chunk mean
   (isolate the hard bar into its own chunk).
5. Merge any chunk of 1 measure with difficulty below the piece median into its neighbour.
```

Design rule: **never cut mid-phrase to hit a round number.** A 5-bar phrase is a 5-bar chunk.

### 5.2 Stage ladder per chunk (F3.2–F3.4)

Default ladder, pruned by content (no left-hand stage if the chunk is right-hand only):

| # | Hand | Tempo | Mode | Criterion |
|---|---|---|---|---|
| 1 | RIGHT | `startPct` | WAIT | 2 consecutive runs ≥ 95 % pitch accuracy |
| 2 | LEFT | `startPct` | WAIT | 2 consecutive runs ≥ 95 % pitch accuracy |
| 3 | BOTH | `startPct` | WAIT | 2 consecutive runs ≥ 95 % pitch accuracy |
| 4 | BOTH | `startPct` | FLOW | 3 consecutive runs ≥ 92 % accuracy, timing RMS ≤ 120 ms |
| 5 | BOTH | ramp → 100 % | FLOW | at each rung: ≥ 90 % accuracy, RMS ≤ 100 ms |
| 6 | BOTH | 100 % | FLOW | 1 run ≥ 95 % accuracy, RMS ≤ 80 ms, no measure > 2 errors |

`startPct = clamp(1.0 - 0.06 × chunkDifficulty, 0.45, 0.85)` — harder chunks start slower.

**Tempo ramp (F3.3):** multiplicative ladder `× 1.10` per rung, floor-clamped to a 5 bpm grid.
On failure at a rung, step back one rung and require one extra clean run before re-attempting —
a standard, well-evidenced practice protocol.

**Joining stages (F3.5):** when chunks *k* and *k+1* both reach `MASTERED`, insert a
`JOIN(k, k+1)` chunk covering both ranges, starting at stage 4 (hands together, flow mode) at
the lower of the two chunks' current tempos. Joins cascade, so the final stage is the whole piece.

### 5.3 Adaptation policy (F3.6)

Evaluated after every attempt:

| Signal | Response |
|---|---|
| 3 consecutive fails at the same stage | Step tempo down one rung |
| 5 consecutive fails at the same stage | Split the chunk at its hardest measure |
| Errors concentrated in ≤ 2 measures (> 60 % of all errors) | Insert a micro-chunk stage over just those bars |
| One hand's accuracy trails the other by > 20 pts | Insert an extra hands-separate stage for the weak hand |
| Rushing bias > +60 ms sustained | Force WAIT mode + metronome for the next run |
| Passed first attempt at 3 consecutive stages | Skip the next redundant stage (accelerate) |
| Chunk `MASTERED` and untouched for 7 days | Mark `NEEDS_REVIEW`, schedule a review stage |

### 5.4 Difficulty model (F2.4)

Per measure, a feature vector — each feature normalised to 0–1 against a reference corpus:

| Feature | Definition |
|---|---|
| `noteDensity` | notes per beat |
| `minIOI` | shortest inter-onset interval (inverse-scaled) |
| `maxSpan` | largest simultaneous hand span in semitones |
| `polyphony` | mean simultaneous voices per hand |
| `handIndependence` | rhythmic dissimilarity between hands (normalised edit distance over onset grids) |
| `accidentalRate` | out-of-key accidentals per note |
| `leapSize` | mean absolute melodic interval > 2 semitones |
| `rhythmComplexity` | entropy of the duration distribution + tuplet/syncopation flags |
| `positionShifts` | detected hand-position changes per measure (from fingering) |
| `ornamentCount` | trills, mordents, grace notes |

`difficulty = clamp(10 × Σ wᵢ·fᵢ, 0, 10)`.

Weights `w` are calibrated once by fitting per-measure aggregates against the
`piano-syllabus-classifier` global grade on the fixture corpus, then frozen and versioned
(`analysisVersion`). Any weight change bumps the version and marks existing plans as
regenerable — plans are never silently rewritten under a learner.

---

## 6. System architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Browser — Angular 21 (standalone, signals, zoneless), SSR              │
│                                                                         │
│  Feature shells:  library · upload · review · plan · practice · progress│
│  Signal stores:   ScoreStore TransportStore PracticeStore FeedbackStore │
│                   DeviceStore                                           │
│  Engines:         OSMD renderer · PianoRoll (canvas) · Tone.js transport│
│                   SpessaSynth · WebMIDI in/out · ScoreIndexService      │
│  Generated client from openapi/api.yaml                                 │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │ REST + SSE
┌────────────────────────────────┴────────────────────────────────────────┐
│  API — Spring Boot 3.5 / Java 21                                        │
│                                                                         │
│  catalog     ScoreService AuthorService GenreService  (retained)        │
│  ingestion   IngestionService · JobQueue · confidence gate              │
│  learning    PlanService · ChunkingService · StageLadderService         │
│              AdaptationService                                          │
│  practice    SessionService · AttemptService · ProgressService          │
│  storage     R2/S3 adapter                                              │
│  security    JWT (env-provided secret), ownership + rights enforcement  │
└──────┬───────────────────────────────────────┬──────────────────────────┘
       │ PostgreSQL                            │ queue (Cloud Tasks / Pub-Sub)
       │  score, author, genre, user (existing)│
       │  score_document, learning_plan,       │
       │  practice_session, attempt,           ▼
       │  measure_result, chunk_mastery   ┌──────────────────────────────┐
       │                                  │ Worker — FastAPI (Python 3.11)│
       ▼                                  │                              │
  Cloudflare R2                           │ /ingest  PDF → ScoreDocument │
   raw/{scoreId}/original.pdf             │   pdftoppm → homr | Audiveris│
   derived/{scoreId}/{rev}/score.musicxml │   → relieur merge            │
   derived/{scoreId}/{rev}/score.mid      │   → MuseScore3 normalise     │
   derived/{scoreId}/{rev}/document.json  │   → music21/partitura parse  │
   derived/{scoreId}/{rev}/preview/*.svg  │   → pianoplayer fingering    │
                                          │   → autoharmonizer chords    │
                                          │   → difficulty + alignment   │
                                          │ /analyse  re-analyse only    │
                                          │ /grade    syllabus classifier│
                                          └──────────────────────────────┘
```

### 6.1 Key architectural decisions

| # | Decision | Rationale | Alternative rejected |
|---|---|---|---|
| A1 | **Keep Angular 21; go zoneless + signal stores** | Current major, standalone + signals already available; a rewrite discards the working practice engine | React/Next rewrite — pure cost |
| A2 | **Keep Spring Boot as the API and system of record** | Small, conventional, already tested; owns auth/catalog/persistence | Node BFF — adds a hop, splits the domain |
| A3 | **Replace bash-script orchestration with a typed Python worker service (FastAPI)** | The heavy lifting is Python already (homr, music21, pianoplayer); the shell layer is where the defects live (§S1–S3) | Keep `ProcessBuilder` + bash — untestable, unquoted globs, silent partial failure |
| A4 | **Precompute everything deterministic at ingestion; ship a static index to the client** | Removes the 1 101-line client alignment engine; makes alignment testable and cacheable | Keep client-side analysis — unfixable maintainability (§R4) |
| A5 | **`ScoreDocument` as the single canonical representation** | One schema feeds engraving, playback, analysis, planning and scoring | Re-parse MusicXML per surface — drift guaranteed |
| A6 | **MusicXML is the interchange format; MIDI is the timing/performance format** | Matches the existing pipeline and every tool in it | MEI — better semantics, no ecosystem here |
| A7 | **OSMD for engraving, custom canvas for falling notes** | OSMD is the only cursor-capable web MusicXML renderer; a piano roll is trivial in canvas | Verovio (no cursor); VexFlow direct (we already own dead proof it's a maintenance sink) |
| A8 | **Real queue with per-message workloads, atomic claim, DLQ** | Fixes §R2 (dropped args, double processing, `System.exit`) | Keep the drain-all job — corrupts data under concurrency |
| A9 | **Two OMR engines with a validation arbiter** | homr is fast but weak on grand-staff piano; Audiveris is slower but structurally stronger. Piano is *the* use case | Single engine — accepts the failure mode that kills the product |
| A10 | **Attempt-level telemetry stored server-side** | Adaptation and progress are impossible without it; also the future training set for a learned difficulty model | Client-only stats — no adaptation, no cross-device |
| A11 | **Plans are versioned and immutable; adaptation appends** | A learner must never find their plan silently rewritten | In-place mutation — untraceable, unexplainable |
| A12 | **Extend `openapi/api.yaml`; generate both sides** | Already working; the strongest existing process asset | Hand-written clients |

### 6.2 Confirmed technology choices

**Frontend**

| Concern | Choice |
|---|---|
| Framework | Angular 21 standalone + signals, **zoneless** |
| Sheet rendering | `opensheetmusicdisplay@1.9.x` |
| Falling notes | Canvas 2D, hand-rolled (~400 LOC); no library needed |
| Audio transport | `tone@15` |
| Synthesis | `spessasynth_lib@4` (SoundFont, AudioWorklet) |
| MIDI file parsing | `@tonejs/midi@2` |
| MIDI I/O | **`webmidi@3`** (upgrade from v2) |
| Virtual keyboard | Own component (~250 LOC) replacing the unpinned `@jesperdj/pianokeys` fork |
| Styling | Tailwind 4 + CSS custom properties for theming |
| State | Angular signals + per-domain stores; no NgRx |
| Charts (progress) | Lightweight SVG, hand-rolled; no charting dependency |
| Lint/format | Biome (properly configured) |
| Unit tests | **Vitest** + `@analogjs/vitest-angular` (migrate off Karma) |
| E2E | Playwright (already a dependency) |

**Backend**

| Concern | Choice |
|---|---|
| API | Spring Boot 3.5, **Java 21** |
| Persistence | PostgreSQL 16 + JPA; JSONB for `ScoreDocument` and plan payloads |
| Migrations | Liquibase (continue the existing changelog) |
| Object storage | Cloudflare R2 via the S3 SDK |
| Queue | Google Cloud Tasks (HTTP push → worker) — or Pub/Sub if fan-out is needed later |
| Streaming status | Server-Sent Events (`/score/{id}/ingestion/events`) |
| Auth | Spring Security + java-jwt, **secret from env, fail-fast** |
| API docs | springdoc-openapi (retained) |

**Worker**

| Concern | Choice |
|---|---|
| Service | FastAPI + Uvicorn, containerised (Cloud Run service, not job) |
| Rasterisation | `pdftoppm` (poppler) at 300 dpi |
| OMR engine 1 | **homr** — transformer OMR, fast, good on clean modern engraving |
| OMR engine 2 | **Audiveris 5.x** — mature, better multi-staff/grand-staff structure; arbiter for piano |
| Merge | `relieur` (existing) |
| Normalisation | MuseScore 3 CLI round-trip (existing) |
| Symbolic analysis | **music21** (key, roman numerals, chordify, segmentation) + **partitura** (note↔performance alignment, precise MusicXML→array) |
| Fingering | `pianoplayer` |
| Harmony | `autoharmonizer` (existing) |
| Global grade | `piano-syllabus-classifier` (existing) |
| Alignment | Smith-Waterman ported from `frontend/.../smith-waterman.ts` |

> **On Audiveris (A9):** homr and Audiveris have complementary failure modes. homr is a
> line-oriented ML recogniser strong on melodic single-staff content; Audiveris does explicit
> structural analysis of systems, braces and grand staves. For solo piano — two staves, brace,
> cross-staff beaming — the structural engine matters. Run both when confidence is low and pick
> by validation score (measure-count consistency, time-signature coherence, voice-leading sanity).

---

## 7. Non-functional requirements

| Area | Requirement |
|---|---|
| Ingestion latency | p50 ≤ 60 s, p95 ≤ 5 min for a 12-page PDF |
| Plan generation | ≤ 2 s after analysis completes |
| Practice input latency | MIDI-in → visual feedback ≤ 30 ms; audio-out ≤ 20 ms |
| Cursor accuracy | 0 desync events per 5-minute run on the fixture corpus |
| Score load (client) | ≤ 2.5 s to first interactive cursor for a 64-bar piece |
| Availability | API 99.5 %; ingestion is async and retryable, so worker downtime degrades gracefully |
| Ingestion success | ≥ 90 % of clean 300 dpi PDFs produce a usable document without manual correction |
| Test coverage | ≥ 80 % on `learning`, `ingestion`, alignment and difficulty modules; 100 % of the fixture corpus in CI |
| Accessibility | WCAG 2.2 AA; full keyboard operability of the practice surface; `prefers-reduced-motion` honoured |
| Security | No secrets in source; ownership enforced server-side; uploads rights-gated; rate-limited upload endpoint |
| Privacy | Uploaded scores private by default; attempts are personal data — exportable and deletable |
| Browser support | Chromium ≥ 120 (WebMIDI). Firefox/Safari: playback + follow-along, no MIDI input, with a clear notice |

---

## 8. Data & API surface (delta over the existing contract)

New endpoints to add to `openapi/api.yaml` (existing catalog/account endpoints unchanged):

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/score/{id}/ingestion` | Enqueue ingestion for an uploaded file |
| `GET` | `/score/{id}/ingestion` | Ingestion status + confidence report |
| `GET` | `/score/{id}/ingestion/events` | SSE progress stream |
| `POST` | `/score/{id}/ingestion/review` | Accept, reject, or request re-run with engine *n* |
| `GET` | `/score/{id}/document` | The `ScoreDocument` (cacheable, immutable per revision) |
| `GET` | `/score/{id}/document/index` | Alignment index only (small, hot path) |
| `GET` | `/score/{id}/plan` | Current learning plan for the caller |
| `POST` | `/score/{id}/plan/regenerate` | Regenerate with new learner parameters |
| `PATCH` | `/score/{id}/plan/stages/{stageId}` | User edit: skip / reorder / re-scope |
| `POST` | `/practice/sessions` | Open a session |
| `POST` | `/practice/sessions/{id}/attempts` | Submit an attempt result |
| `POST` | `/practice/sessions/{id}/close` | Close, return the summary |
| `GET` | `/score/{id}/progress` | Progress state for the caller |

Full schemas: [`DATA_PIPELINE.md`](./DATA_PIPELINE.md) §6.

---

## 9. Success metrics

| Metric | Target (6 months post-launch) |
|---|---|
| Upload → usable plan conversion | ≥ 80 % |
| Ingestion requiring manual correction | ≤ 20 % |
| Median stages completed per learner per week | ≥ 6 |
| Pieces reaching the final performance stage | ≥ 25 % of started pieces |
| Median tempo gain from stage 1 to final, per chunk | ≥ +40 % |
| 4-week practice retention | ≥ 35 % |
| Cursor desync reports | < 1 per 1 000 sessions |

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| OMR accuracy insufficient for piano PDFs | Product-fatal | Two engines + confidence gate + review UI + MusicXML upload path (F1.2) as the always-works escape hatch |
| Chunking/difficulty feel musically wrong | Trust loss | Phrase-aware boundaries; user-editable plan (F3.10); validate against the fixture corpus with a pianist |
| Copyright exposure from user uploads | Legal | Private-by-default, explicit rights field, no public sharing of `USER_UPLOAD` scores in v1 |
| WebMIDI is Chromium-only | Reach | Explicit browser support statement; degraded follow-along mode elsewhere |
| Worker cost (torch + MuseScore image, minutes per job) | Cost | CPU-only torch (already), scale-to-zero Cloud Run, per-user upload quotas |
| Refactor stalls before the pivot lands | Schedule | Gate 0/1/2 (`AUDIT_AND_REFACTOR.md` §6) are strictly ordered and independently shippable |
