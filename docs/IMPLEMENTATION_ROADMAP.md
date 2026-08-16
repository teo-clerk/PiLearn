# IMPLEMENTATION_ROADMAP — PiLearn

Phased execution plan for the PianoML → PiLearn pivot. Every task is scoped to be picked up
independently in a follow-up session.

Companion documents: [`AUDIT_AND_REFACTOR.md`](./AUDIT_AND_REFACTOR.md) ·
[`PRODUCT_SPEC.md`](./PRODUCT_SPEC.md) · [`DATA_PIPELINE.md`](./DATA_PIPELINE.md)

---

## Conventions

- **Task IDs** are stable (`P2-T04`). Reference them in commits and PRs.
- **DoD** = Definition of Done. A task is not complete until its DoD holds *and* CI is green.
- **Estimates** assume one focused engineer (or one agent session with review). `d` = day.
- **Gate** tasks block everything after them in the phase.
- Every phase ends in a demoable, deployable state. No phase leaves `main` broken.

**Standing rules for all phases**

- TDD: failing test first, then implementation. Coverage floor 80 % on new modules.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`).
- No secrets in source. No `any`. No mutation of shared state.
- Files ≤ 800 LOC hard cap; components ≤ 300; services ≤ 400.
- Extend `openapi/api.yaml` and regenerate — never hand-write a client.

---

## Phase overview

| Phase | Theme | Duration | Exit criterion |
|---|---|---|---|
| **1** | Teardown & Setup | 3 weeks | Repo under version control, CI green, dead code gone, golden corpus locked, secrets fixed |
| **2** | Core PDF/Score Ingestion | 5 weeks | Upload a PDF → a validated `ScoreDocument` + alignment index, with live progress and a confidence gate |
| **3** | Learning Logic Engine | 4 weeks | A `ScoreDocument` yields a musically credible `LearningPlan`; attempts persist; adaptation works |
| **4** | UI/UX & Interactive Practice | 6 weeks | Full hero flow usable end-to-end: upload → review → plan → practise → progress |
| **5** | Polish, Hardening & Launch | 4 weeks | Accessibility, performance, migration of legacy scores, e2e suite, production readiness |
| | **Total** | **~22 weeks** | |

Phases 2 and 3 overlap by ~1 week (the plan generator can be developed against fixture documents
before ingestion is fully wired).

---

## Phase 1 — Teardown & Setup (3 weeks)

**Goal:** make change safe, then delete everything that is dead. No behaviour changes to the
shipping product.

### Milestone 1.1 — Version control & CI *(gate)*

| ID | Task | Est | DoD |
|---|---|---|---|
| P1-T01 | `git init`; `.gitignore` for `node_modules`, `target`, `dist`, `.angular`, `Scores/`, `*local*.properties`; initial commit of the current tree | 0.5d | `git status` clean; no binary/secret tracked |
| P1-T02 | Branch model: `main` protected, `feat/*` branches, PR required | 0.25d | Documented in `CONTRIBUTING.md` |
| P1-T03 | CI workflow: `mvn -B verify` (backend), `npm ci && npm run build` + `biome ci` (frontend), on push and PR | 1d | Both jobs green on a clean checkout |
| P1-T04 | Secret scanning (gitleaks) + dependency audit (`npm audit`, OWASP dependency-check) in CI | 0.5d | Fails the build on a detected secret |
| P1-T05 | Pin the Dockerfile's git-cloned tools (homr, relieur, pianoplayer, autoharmonizer) to commit SHAs | 0.5d | Two consecutive image builds produce identical tool versions |

### Milestone 1.2 — Security & compliance *(gate)*

| ID | Task | Est | DoD |
|---|---|---|---|
| P1-T06 | Remove the `jwt.secret` default from `application.properties`; require `JWT_SECRET` via `@ConfigurationProperties` + `@Validated` + `@NotBlank`; fail fast at startup | 0.5d | App refuses to boot without the env var; a test asserts this |
| P1-T07 | Move R2 endpoint, bucket name and datasource URL to env vars with no committed defaults | 0.5d | `application.properties` contains no environment-specific value |
| P1-T08 | Rotate the JWT signing key in every environment; invalidate existing tokens | 0.25d | Confirmed by the operator |
| P1-T09 | Move `Scores/*` to a private fixtures bucket; keep `fixtures/manifest.json` (name, sha256, rights) in-repo; gitignore the directory | 0.5d | No copyrighted binary in the tree; a fetch script pulls the corpus for local dev |
| P1-T10 | Add `rights` to `Score` (`PUBLIC_DOMAIN`/`LICENSED`/`USER_UPLOAD_PRIVATE`); flip `public_domain` default to `false` (Liquibase changeset 021) | 0.5d | Migration applies forward and rolls back; new uploads default to private |

### Milestone 1.3 — Golden characterization harness *(gate)*

| ID | Task | Est | DoD |
|---|---|---|---|
| P1-T11 | Harness that runs the *existing* `pdf2pack.sh` over the 10 fixture PDFs and snapshots `metadata.json`, measure count, note count, duration | 2d | `npm run fixtures:snapshot` produces stable output across two runs |
| P1-T12 | Commit the snapshots as golden files; add a CI job that diffs against them (nightly — too slow for every push) | 1d | An intentional change to a script fails the nightly job |
| P1-T13 | Manual quality baseline: for each fixture, record OMR accuracy against the printed score (measure count, key, time sig, obvious wrong notes) | 1d | `docs/OMR_BASELINE.md` with a per-fixture table — this is the number Phase 2 must beat |

> P1-T11–13 are the highest-leverage tasks in the phase. Without them, every Phase 2 change to the
> pipeline is an unverifiable guess.

### Milestone 1.4 — Deletions

| ID | Task | Est | DoD |
|---|---|---|---|
| P1-T14 | Port the chord/hand-splitting heuristic out of `hand-detector.service.ts` into `worker/analysis/hands.py` **with unit tests**, before deleting anything | 2d | Python implementation reproduces the TS output on 20 recorded cases |
| P1-T15 | Delete `animated-score/`, `pianoman/`, `svg-icon/`, `engraving.service.ts`, `hand-detector.service.ts`, `rest-filler.ts` | 0.5d | Build green; no dangling import |
| P1-T16 | Drop `vexflow`, `nouislider`, `wnumb`, `@types/wnumb`, `@criblinc/docker-names`, `axios` (verify zero call sites first); move `@types/*` to `devDependencies` | 0.5d | `npm ci && npm run build` green; bundle size recorded before/after |
| P1-T17 | Delete `backend/scripts/midi2pack1.sh`, `test.sh`, `convert.py`; delete the 21 stub spec files | 0.5d | Nightly golden job still green |
| P1-T18 | Configure Biome properly (rules, formatter, import order) and apply once repo-wide | 1d | `biome ci` green; a single formatting commit, separate from logic changes |

### Milestone 1.5 — Test infrastructure

| ID | Task | Est | DoD |
|---|---|---|---|
| P1-T19 | Migrate the frontend test runner Karma/Jasmine → Vitest (`@analogjs/vitest-angular`) | 1.5d | `npm test` runs the surviving specs under Vitest; coverage reporting works |
| P1-T20 | Add `playwright.config.ts` + one smoke journey (home → library → score detail) | 1d | Playwright runs headless in CI |
| P1-T21 | Backfill unit tests for `smith-waterman.ts` before it is ported (characterization) | 1.5d | ≥ 15 cases covering exact/partial/gap/mismatch; both `sw1` and `sw2` |

**Phase 1 exit criteria**

- [ ] Repo in git, CI green on push, secret scanning active
- [ ] No hardcoded secrets; no copyrighted binaries tracked
- [ ] ~1 200 LOC of dead frontend code and 6 dependencies removed
- [ ] Golden corpus harness runs nightly and is green
- [ ] `docs/OMR_BASELINE.md` records the pre-pivot OMR quality baseline
- [ ] Vitest + Playwright wired; Smith-Waterman characterized

---

## Phase 2 — Core PDF/Score Ingestion (5 weeks)

**Goal:** a PDF upload produces a validated, analysed `ScoreDocument` with a precomputed alignment
index, via a real job queue, with live progress and a confidence gate.

### Milestone 2.1 — Worker service skeleton *(gate)*

| ID | Task | Est | DoD |
|---|---|---|---|
| P2-T01 | Scaffold `worker/` — FastAPI + Uvicorn, Dockerfile derived from the existing OMR image, `/health`, `/ingest` | 2d | Container builds; `/health` responds; image is reproducible |
| P2-T02 | Typed stage contract: `Stage` protocol with `(input, params) → output + events`, content-addressed caching per `(inputHash, stageVersion, params)` | 2d | Re-running an unchanged stage is a cache hit; unit-tested |
| P2-T03 | Port P1 Rasterise from `pdf2pack.sh` — quoted paths, page-count and DPI capture, hard failure on partial output | 1d | Handles the fixture filenames containing spaces and accents |
| P2-T04 | Port P2 Recognise (homr) with per-page confidence extraction; **fail the document when any page is dropped** | 3d | A deliberately corrupted page yields `PAGE_DROPPED`, not a silent short score |
| P2-T05 | Port P3 Merge (relieur) with post-condition assertions (monotone measure numbers, constant part count) | 1d | Seam violations are reported with the offending measure |
| P2-T06 | Port P4 Normalise — MuseScore3 round-trip **through `.mscz` only** (removes the legacy MIDI round-trip that destroys voicing) | 1.5d | Golden diff shows voicing/ties preserved vs the legacy output |

### Milestone 2.2 — Validation & confidence

| ID | Task | Est | DoD |
|---|---|---|---|
| P2-T07 | Implement P5 Validate: the 8 structural checks, `ConfidenceReport`, the three status bands | 2.5d | Each issue code has a fixture that triggers it |
| P2-T08 | Audiveris integration as engine B + the arbitration policy | 3d | On a fixture where homr mangles the grand staff, arbitration selects Audiveris |
| P2-T09 | Compare engines over the whole corpus; record results in `docs/OMR_BASELINE.md` | 1d | Documented per-fixture win/loss vs the Phase 1 baseline |

### Milestone 2.3 — Enrichment & document build

| ID | Task | Est | DoD |
|---|---|---|---|
| P2-T10 | Port P6 Enrich: pianoplayer fingering, autoharmonizer harmony (both gated on "already present"), music21 key/roman/cadence | 2d | Existing fingering/harmony is never overwritten |
| P2-T11 | P7 hand assignment using the ported heuristic (P1-T14) + staff mapping | 2d | Correct on cross-staff fixtures (Debussy, Hisaishi) |
| P2-T12 | P7 repeat unrolling → `playbackOrder`; **replace the magic iteration guards with explicit structural validation** | 3d | Fixtures for simple repeat, 1st/2nd volta, D.C. al Fine, D.S. al Coda, nested repeats all pass; an unresolvable structure raises `UNRESOLVED_REPEAT` |
| P2-T13 | Port Smith-Waterman to Python; build the alignment index (`TimelineStep[]`, `byTick`, `byMeasure`) | 3d | Identity property test passes; matches the characterization cases from P1-T21 |
| P2-T14 | Emit `ScoreDocument` v1.0 + `AlignmentIndex`; JSON-schema validated | 2d | All 10 fixtures produce schema-valid documents |

### Milestone 2.4 — Orchestration & API

| ID | Task | Est | DoD |
|---|---|---|---|
| P2-T15 | Liquibase 022–026: `ingestion_job`, `ingestion_event`, `score_document`; `score.current_revision`, `score.document_confidence` | 1d | Forward + rollback tested |
| P2-T16 | `IngestionService`: atomic claim (`UPDATE ... WHERE status='QUEUED' RETURNING`), lease, retry budget, dead-letter | 2.5d | Concurrency test: 10 parallel triggers process the job exactly once |
| P2-T17 | Replace `CloudRunJobService` + `WorkloadProcessingService` with Cloud Tasks → worker HTTP push. **Delete `System.exit(0)`.** | 2d | Old job path removed; `workload` table marked deprecated |
| P2-T18 | Lease sweeper (scheduled) reclaiming expired `RUNNING` jobs | 0.5d | A killed worker's job returns to `QUEUED` within one sweep interval |
| P2-T19 | Split raw and derived storage prefixes; **stop overwriting the upload key with the result** | 1d | The original PDF survives ingestion; a test asserts it |
| P2-T20 | Extend `openapi/api.yaml`: `/score/{id}/ingestion`, `/ingestion/events` (SSE), `/document`, `/document/index`; regenerate both clients | 1.5d | Generated client compiles; contract tests validate responses |
| P2-T21 | SSE progress endpoint backed by `ingestion_event` with replay from `seq` | 1.5d | Reconnecting mid-ingestion resumes without gaps |

### Milestone 2.5 — Client integration

| ID | Task | Est | DoD |
|---|---|---|---|
| P2-T22 | `ScoreIndexService` — fetch `index.json`, `O(1)` step lookup. **Reduce `cursor.service.ts` from 1 101 → ≤ 150 LOC** | 3d | Playback on all 10 fixtures tracks the cursor with zero desync over a full run |
| P2-T23 | Rewrite the upload UI: drag-drop, client-side validation, live SSE stage progress | 2d | Progress reflects real stages, not a spinner |
| P2-T24 | `ScoreStore` signal store (document, index, load state) replacing ad-hoc component state | 1.5d | No component reads OSMD/MIDI state directly |

**Phase 2 exit criteria**

- [ ] Upload a PDF → `ScoreDocument` + `AlignmentIndex` persisted, with live progress
- [ ] Confidence report produced; low-confidence documents halt at `REVIEW_REQUIRED`
- [ ] Job processing is exactly-once under concurrency; no `System.exit`; originals preserved
- [ ] `cursor.service.ts` ≤ 150 LOC; zero desync on the fixture corpus
- [ ] OMR quality measurably ≥ the Phase 1 baseline (documented)

---

## Phase 3 — Learning Logic Engine (4 weeks)

**Goal:** turn a `ScoreDocument` into a musically credible, adaptive `LearningPlan`, and persist
what actually happens during practice.

### Milestone 3.1 — Difficulty & segmentation

| ID | Task | Est | DoD |
|---|---|---|---|
| P3-T01 | Implement the 10 per-measure difficulty features (P8) | 3d | Synthetic-measure unit tests fix each feature's value |
| P3-T02 | Calibrate feature weights against `piano-syllabus-classifier` global grades on the corpus; freeze as `weightsVersion` | 2d | Rank correlation ≥ 0.7 between aggregated measure difficulty and global grade |
| P3-T03 | Phrase/cadence segmentation (music21 chordify + cadence heuristics + rest/double-barline/texture signals) | 3d | Segment boundaries reviewed against the printed scores by a pianist; ≥ 80 % agreement |
| P3-T04 | Technical-pattern detection (scale run, arpeggio, broken chord, octave leap, trill, cross-hand, syncopation, polyrhythm) | 2d | Labelled fixture measures classified correctly |

### Milestone 3.2 — Plan generation

| ID | Task | Est | DoD |
|---|---|---|---|
| P3-T05 | Chunking algorithm (`PRODUCT_SPEC` §5.1) | 2.5d | Property tests: no chunk crosses a phrase boundary; size bounds hold; hard bars are isolated |
| P3-T06 | Stage ladder generator (§5.2), including content-based pruning and `startPct` derivation | 2d | A right-hand-only chunk produces no left-hand stage |
| P3-T07 | Tempo ramp ladder + mastery criteria per stage | 1.5d | Ramp rungs land on a 5 bpm grid; final rung equals the target tempo |
| P3-T08 | Join stages: cascading merge of adjacent mastered chunks up to the whole piece | 2d | A 4-chunk piece produces the expected join tree ending in one whole-piece stage |
| P3-T09 | `LearningPlan` v1 emission, versioned and immutable | 1d | Schema-valid plans for all 10 fixtures |
| P3-T10 | Plan review with a pianist over 3 fixtures; tune chunk sizes and criteria | 2d | Written sign-off in `docs/PLAN_REVIEW.md` |

### Milestone 3.3 — Telemetry & progress

| ID | Task | Est | DoD |
|---|---|---|---|
| P3-T11 | Liquibase 027–031: `learning_plan`, `practice_session`, `attempt`, `measure_result`, `chunk_mastery` | 1d | Forward + rollback tested |
| P3-T12 | `/practice/sessions`, `/attempts`, `/close`, `/score/{id}/progress`, `/plan`, `/plan/regenerate`, `PATCH /plan/stages/{id}` in `api.yaml`; regenerate clients | 1.5d | Contract tests green |
| P3-T13 | `AttemptService`: scoring an attempt (accuracy, timing RMS, rush bias, per-measure results) and evaluating it against the stage criterion | 2.5d | Verdict table (`PASS`/`RETRY`/`STEP_DOWN`) fully covered by unit tests |
| P3-T14 | `ProgressService`: chunk mastery state machine, tempo curve, weakest measures | 2d | State transitions unit-tested including `NEEDS_REVIEW` decay |
| P3-T15 | `AdaptationService`: the 7 adaptation rules (§5.3), emitting a new plan version with `derivedFrom` + reason | 3d | Simulated attempt sequences drive each rule exactly once; plans are never mutated in place |
| P3-T16 | Spaced review scheduling (`next_review_at`) | 1d | A mastered chunk untouched for 7 days is scheduled for review |

### Milestone 3.4 — Client-side practice policy

| ID | Task | Est | DoD |
|---|---|---|---|
| P3-T17 | Extract `PracticePolicyService` from `player.service.ts`: stage-aware wait/flow gating, hand filtering, chunk looping | 2.5d | `player.service.ts` ≤ 300 LOC; policy is unit-tested without a browser |
| P3-T18 | Extend `player-assess.service.ts` to emit `MeasureResult[]` and per-note `NoteEvent[]` attributed via the alignment index | 2d | An attempt over a fixture yields the expected per-measure counts |
| P3-T19 | `PracticeStore` + `FeedbackStore` signal stores; buffer attempts and flush on completion (IndexedDB fallback) | 2d | An offline attempt syncs on reconnect |

**Phase 3 exit criteria**

- [ ] Every fixture yields a plan a pianist agrees is sensible
- [ ] Attempts persist with per-measure detail; progress and mastery update correctly
- [ ] All 7 adaptation rules fire correctly against simulated sequences
- [ ] Plans are versioned and immutable; adaptation is auditable

---

## Phase 4 — UI/UX & Interactive Practice (6 weeks)

**Goal:** the complete hero flow, usable end-to-end, on a rebuilt practice surface.

### Milestone 4.1 — Foundations

| ID | Task | Est | DoD |
|---|---|---|---|
| P4-T01 | Design system: tokens (colour, spacing, type, motion), light/dark, component primitives; re-evaluate FlyonUI vs Tailwind-only | 3d | Tokens documented; contrast verified AA in both themes |
| P4-T02 | Migrate to **zoneless** change detection; remove `zone.js` | 2d | App runs zoneless; no `ExpressionChanged` regressions; a11y smoke passes |
| P4-T03 | App shell + navigation refresh: library → piece → plan → practice → progress | 2d | Breadcrumbs and deep links work under SSR |

### Milestone 4.2 — Upload & review

| ID | Task | Est | DoD |
|---|---|---|---|
| P4-T04 | Upload screen: drag-drop, file validation, metadata form with MusicBrainz autofill, ingestion options | 2d | Rejects oversize/encrypted/too-many-pages before upload |
| P4-T05 | Ingestion progress screen driven by SSE, with per-stage detail and failure explanations | 2d | A forced failure shows an actionable message, not a stack trace |
| P4-T06 | **Review gate UI**: PDF page ↔ engraved SVG side-by-side, per-measure confidence heat strip, accept / flag / re-run-with-other-engine / upload-MusicXML-instead | 4d | A low-confidence fixture can be resolved through every one of the four paths |
| P4-T07 | Measure correction UI (F1.8, P2 priority — cut if schedule pressure) | 4d | Editing a note's pitch/duration/hand triggers re-analysis of that measure only |

### Milestone 4.3 — Plan surface

| ID | Task | Est | DoD |
|---|---|---|---|
| P4-T08 | Plan overview: chunk timeline over the score, stage list, estimates, mastery badges | 3d | A 64-bar piece's plan is readable without scrolling fatigue |
| P4-T09 | Plan editing: reorder, skip, re-scope a stage; regenerate with new params | 2d | Edits persist and are marked `userEdited`; regeneration warns before discarding edits |
| P4-T10 | Piece dashboard: overall %, tempo curve, weakest-bars heat map over the engraved score | 3d | Heat map colours match `measure_result` aggregates |

### Milestone 4.4 — Practice surface (the core rebuild)

| ID | Task | Est | DoD |
|---|---|---|---|
| P4-T11 | Decompose `workbench.component.ts` (1 009 LOC) → `PracticeShell` + `PracticeStageHost` + `TransportBar` + `ScoreViewport` + `PracticeHud` + `SessionSummary` | 4d | No component > 300 LOC; behaviour parity verified by the Playwright journey |
| P4-T12 | Stage-scoped score viewport: chunk highlight, context dimming, auto-scroll to the chunk | 2.5d | Entering a stage scrolls to and highlights exactly its measure range |
| P4-T13 | Practice HUD: stage goal, run counter, live accuracy, rushing/dragging meter | 2.5d | Meter responds within 100 ms of played notes |
| P4-T14 | Live note feedback colouring: correct / early / late / wrong / missed, on both notation and keyboard | 3d | All five classifications visibly distinct and colour-blind safe |
| P4-T15 | Count-in, metronome, stage-driven tempo default, chunk loop | 1.5d | Tempo and loop bounds come from the stage, not from user state |
| P4-T16 | Run summary modal: verdict, stats, per-measure errors, next action | 2d | Verdict matches `AttemptService` server-side evaluation |
| P4-T17 | **Falling-notes view** (canvas piano roll) as a toggleable alternative to sheet view | 4d | 60 fps at 3 000 notes; shares the transport and feedback state with sheet view |
| P4-T18 | Replace `@jesperdj/pianokeys` (unpinned git fork) with an owned keyboard component | 2.5d | Visual parity; 88-key and windowed ranges; touch-capable |
| P4-T19 | Upgrade `webmidi` v2 → v3; device picker + input latency calibration wizard | 2d | Calibration measurably reduces timing bias on a real keyboard |

### Milestone 4.5 — Session flow

| ID | Task | Est | DoD |
|---|---|---|---|
| P4-T20 | Session lifecycle: open → stages → close, with a summary and "next time start here" | 2d | Sessions persist; an abandoned session closes cleanly on reload |
| P4-T21 | Adaptive transitions surfaced in the UI ("tempo lowered to 54 bpm — bars 33-36 need work") | 1.5d | Every adaptation rule produces a human-readable explanation |
| P4-T22 | Degraded mode without MIDI: playback + follow-along, scoring disabled, explicit notice | 1d | Firefox/Safari usable for follow-along |

**Phase 4 exit criteria**

- [ ] Upload → review → plan → practise → progress works end-to-end on all 10 fixtures
- [ ] Practice surface fully decomposed; no component over 300 LOC
- [ ] Both sheet and falling-notes views share transport and feedback state
- [ ] Zoneless, tokenised design system, AA contrast in both themes

---

## Phase 5 — Polish, Hardening & Launch (4 weeks)

### Milestone 5.1 — Quality

| ID | Task | Est | DoD |
|---|---|---|---|
| P5-T01 | Test backfill to the 80 % floor on `ingestion`, `learning`, alignment, difficulty | 4d | Coverage gate enforced in CI |
| P5-T02 | Playwright journeys: upload→plan, practise-a-stage, adaptation-triggers, review-gate, progress | 3d | All five green in CI against a seeded environment |
| P5-T03 | Load test ingestion: 50 concurrent uploads | 1.5d | No duplicate processing; queue drains; p95 within the NFR |
| P5-T04 | Security review: authz on every new endpoint, ownership checks, upload rate limiting, SSRF/path-traversal on file handling | 2d | `security-reviewer` pass with no CRITICAL/HIGH open |
| P5-T05 | Fix the residual defects catalogued in `AUDIT_AND_REFACTOR.md` §5 (S4–S8, S19, S20) | 2d | Each has a regression test |

### Milestone 5.2 — Accessibility & performance

| ID | Task | Est | DoD |
|---|---|---|---|
| P5-T06 | WCAG 2.2 AA audit and remediation; full keyboard operability of the practice surface; `prefers-reduced-motion` | 3d | Axe clean; screen-reader walkthrough of the hero flow documented |
| P5-T07 | Performance: score load ≤ 2.5 s, MIDI→visual ≤ 30 ms, 60 fps piano roll; bundle budget | 2.5d | Lighthouse ≥ 90 performance on the practice route; budgets enforced in CI |
| P5-T08 | SSR correctness for the new routes (no `window`/`localStorage` on the server path) | 1d | SSR build renders every route without hydration errors |

### Milestone 5.3 — Migration & operations

| ID | Task | Est | DoD |
|---|---|---|---|
| P5-T09 | Legacy score backfill (`DATA_PIPELINE` §9) with dry-run, rate limiting and resume | 3d | Dry run reports the P5-failure count; full run completes; failures are individually retryable |
| P5-T10 | Retire the `workload` table and the last Cloud Run Job remnants | 0.5d | No references remain |
| P5-T11 | Observability: structured logs, ingestion metrics (duration by stage, confidence distribution, failure codes), alerting on queue depth and DLQ | 2d | Dashboard exists; an alert fires in a drill |
| P5-T12 | Runbooks: stuck job, OMR regression, key rotation, backfill resume | 1d | `docs/RUNBOOKS.md` |

### Milestone 5.4 — Product polish

| ID | Task | Est | DoD |
|---|---|---|---|
| P5-T13 | Onboarding: first-run tour of the plan and practice surfaces (replacing the legacy workbench tour) | 2d | New user reaches their first practice stage without help |
| P5-T14 | Empty, loading, error and offline states across every new surface | 1.5d | No raw spinner-only or blank state remains |
| P5-T15 | i18n scaffolding; unify the FR/EN mix on one locale as the source | 2d | All user-facing strings extracted; one locale complete |
| P5-T16 | Update `README.md`, `CONTRIBUTING.md`, and the four `docs/` files to as-built state | 1d | A new contributor can set up and run everything from the README |

**Phase 5 exit criteria**

- [ ] Coverage floor met; 5 e2e journeys green; load test passed
- [ ] WCAG 2.2 AA verified; performance NFRs met
- [ ] Legacy scores migrated; old job path fully retired
- [ ] Observability, alerting and runbooks in place
- [ ] Documentation matches the built system

---

## Dependency graph (critical path)

```
P1-T01 ─► P1-T03 ─► P1-T11 ─► P1-T13 ────────────────┐
   │                                                  │  (baseline needed to judge P2 quality)
   └─► P1-T06 (secrets)                               │
   └─► P1-T14 ─► P1-T15 (port before delete)          │
                                                      ▼
P2-T01 ─► P2-T02 ─► P2-T03 ─► P2-T04 ─► P2-T05 ─► P2-T06 ─► P2-T07 ─► P2-T10
                                                                          │
                                        P2-T11 ─► P2-T12 ─► P2-T13 ─► P2-T14
                                                                          │
                    P2-T15 ─► P2-T16 ─► P2-T17 ─► P2-T20 ─► P2-T21 ─► P2-T22
                                                                          │
P3-T01 ─► P3-T02 ─┐                                                       │
P3-T03 ───────────┴─► P3-T05 ─► P3-T06 ─► P3-T07 ─► P3-T08 ─► P3-T09 ─► P3-T10
                                                                          │
                            P3-T11 ─► P3-T13 ─► P3-T14 ─► P3-T15          │
                                                                          ▼
P4-T02 ─► P4-T11 ─► P4-T12 ─► P4-T13 ─► P4-T14 ─► P4-T16 ─► P4-T20 ─► P4-T21
                                                                          │
                                                                          ▼
                                                    P5-T01 ─► P5-T02 ─► P5-T09
```

**Parallelisable tracks:** P4-T01/T03 (design system) can start during Phase 3. P4-T17
(falling notes) and P4-T18 (keyboard) are independent of the plan surface. P3-T01–T04 (analysis)
can run against fixture documents before P2 ingestion is complete.

---

## Risk register (schedule)

| Risk | Trigger | Mitigation | Fallback |
|---|---|---|---|
| OMR quality plateau below usable | P2-T09 shows no gain over baseline | Two-engine arbitration + review gate | Ship with MusicXML/MIDI upload as the primary path; PDF marked beta |
| Repeat unrolling proves intractable on real scores | P2-T12 fixtures keep failing | Explicit validation + `UNRESOLVED_REPEAT` status | Fall back to linear playback order with a UI warning |
| Chunking judged musically wrong | P3-T10 pianist review fails | Weight tuning + segmentation rework | Ship fixed-size chunking with prominent user editing |
| Practice surface rebuild regresses playback | P4-T11 parity check fails | Playwright parity journey written *before* decomposition | Keep the legacy workbench route alive behind a flag |
| Phase 4 overruns | Any P4 milestone slips > 1 week | Cut P4-T07 (correction UI) and P4-T17 (falling notes) to post-launch | Both are P1/P2 priority, not P0 |

---

## Immediate next actions

The first three tasks, in order, with nothing else started before them:

1. **P1-T01** — `git init` + `.gitignore` + initial commit. Nothing else is safe until this exists.
2. **P1-T06** — remove the committed JWT secret and rotate it.
3. **P1-T11** — build the golden characterization harness over the fixture corpus.
