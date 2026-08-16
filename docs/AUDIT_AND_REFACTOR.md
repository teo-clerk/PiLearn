# AUDIT_AND_REFACTOR

Architectural audit of the existing **PianoML** codebase (`/home/cl3rk/CODING/PiLearn`) as the
starting point for the **PiLearn** pivot: *upload a sheet-music PDF → process it → get an
interactive, step-by-step learning roadmap*.

Audit date: 2026-08-16 · Frontend `piano-ml@2.0.3` · Backend `org.pianoml:backend:2.0.3-SNAPSHOT`

---

## 1. Executive summary

The legacy project is **not** a throwaway. It is a working, deployed piano-practice application
that already owns roughly 70–80 % of the *infrastructure* the target product needs:

- a real PDF → OMR → MusicXML → MIDI ingestion pipeline (homr + relieur + MuseScore3 + pianoplayer),
- object storage, an async workload queue, and a batch worker container,
- a browser sheet-music renderer with a playing cursor (OpenSheetMusicDisplay),
- a MIDI playback engine (Tone.js transport + SpessaSynth SoundFont synth),
- live WebMIDI keyboard input with note-level hit/miss/early/late assessment,
- a non-trivial cursor↔MIDI alignment engine (Smith-Waterman + repeat/volta/D.C. unrolling).

What is **entirely absent** is the *pedagogy layer*. There is no per-measure difficulty analysis,
no notion of a practice stage or chunk, no session/attempt persistence, no progress model, no
adaptive tempo ramp, and no scheduling. The single practice surface is a 1 009-line free-play
"workbench" with three manual toggles (loop, wait-for-left-hand, wait-for-right-hand) and a
measure-range slider.

**The pivot is therefore additive, not a rewrite.** Keep and harden the ingestion + playback core.
Extract the fragile client-side score analysis to the server. Build a new Learning Engine domain.
Rebuild the practice UI around stages instead of a single free-play screen.

**Biggest risks found (detail in §5):**

| # | Risk | Severity |
|---|------|----------|
| R1 | Hardcoded JWT signing secret committed in `application.properties` | **Critical** |
| R2 | OMR worker ignores its job arguments; drains *all* pending workloads and calls `System.exit(0)` | **High** |
| R3 | Zero real test coverage on the three most complex modules (cursor alignment, player, assessment) | **High** |
| R4 | 1 101-line client-side alignment engine with `while` loops guarded by magic iteration caps | **High** |
| R5 | Copyrighted sheet music committed to the repo; `publicDomain` defaults to `true` | **High** (legal) |
| R6 | No git repository, no CI, no e2e tests despite a Playwright dependency | **High** |
| R7 | OMR output has no confidence signal and no human correction step | **High** (product) |

---

## 2. Current architecture

```
┌──────────────── frontend/ (Angular 21.2, SSR via Express 4) ───────────────┐
│  home · library · score-info · import · account · blog · exercises        │
│  desktop/ (the practice surface)                                          │
│    workbench.component.ts (1009)  ── OSMD component ── keyboard (pianokeys)│
│    service/ cursor(1101) player(576) player-audio(494) hand-detector(475)  │
│             smith-waterman(489) music-theory(440) engraving(405)[dead]     │
│  core/api/  ← generated from openapi/api.yaml (openapi-generator, 3701 LOC)│
└───────────────────────────────┬───────────────────────────────────────────┘
                                │ REST (proxy.conf.json → :8080)
┌───────────────────────────────┴───────────────────────────────────────────┐
│  backend/ Spring Boot 3.5.4 · Java 17 · 71 files / 4 839 LOC              │
│    controller → service → repository (JPA) → PostgreSQL (Liquibase, 21 cs)│
│    ScoreService(492) ScoreRepositoryImpl(452) ScoreController(363)         │
│    PackService(224) WorkloadProcessingService(146) CloudRunJobService(95)  │
│    security: JWT (auth0 java-jwt) · S3 via spring-cloud-aws → Cloudflare R2│
└───────────────────────────────┬───────────────────────────────────────────┘
                                │ triggers Cloud Run Job (profile=job)
┌───────────────────────────────┴───────────────────────────────────────────┐
│  OMR worker container (debian-12-slim, backend/Dockerfile)                │
│    homr (transformer OMR) · relieur (MusicXML concat) · MuseScore3         │
│    pianoplayer (fingering) · autoharmonizer · music21/miditok/pretty_midi  │
│    piano-syllabus-classifier (global 0–8 grade, LightGBM)                  │
│    orchestrated by 12 bash + 10 python scripts (1 775 LOC total)           │
└───────────────────────────────────────────────────────────────────────────┘
                                │
                        Cloudflare R2 bucket `pianoml-media`
                        one ZIP per score: {pdf, midi, musicxml, metadata.json}
```

### Tech stack inventory

| Layer | Technology | Version | Verdict |
|---|---|---|---|
| Frontend framework | Angular (standalone, signals, SSR) | 21.2.11 | **Keep** — current LTS-line, already signal-capable |
| Change detection | zone.js | 0.15 | **Migrate** → zoneless |
| Styling | Tailwind 4.2 + FlyonUI 1.3 + PostCSS | — | Keep Tailwind; **re-evaluate FlyonUI** |
| Engraving | OpenSheetMusicDisplay | 1.9.7 | **Keep** — only mature web MusicXML renderer with a cursor |
| Engraving (2nd) | VexFlow | 5.0.0 | **Drop as direct dep** — OSMD embeds it; our direct use is dead code |
| Audio transport | Tone.js | 15.1.22 | **Keep** |
| Synth | spessasynth_lib (SoundFont, AudioWorklet) | 4.2.10 | **Keep** |
| MIDI parsing | @tonejs/midi | 2.0.28 | **Keep** |
| MIDI writing | midi-writer-js | 3.1.1 | Keep (exercise generator) |
| MusicXML (TS) | @stringsync/musicxml | 0.3.0 | Keep (exercise generator only) |
| MIDI input | webmidi 2.5 + @types/webmidi | 2.5.3 | **Upgrade** — v2 is legacy; v3 API is materially better |
| Keyboard render | @jesperdj/pianokeys (git fork) | — | **Replace** — unpinned GitHub dep, no versioning |
| Sliders | nouislider + wNumb | 15.8 | **Drop** — replaceable by native range input |
| Lint | Biome | (via devDeps, 8-line config) | **Keep, configure properly** |
| Test (unit) | Karma + Jasmine | 6.4 / 5.1 | **Migrate** → Vitest (Karma is deprecated) |
| Test (e2e) | @playwright/test | 1.59 | **Dependency present, zero tests** |
| Backend | Spring Boot / Java | 3.5.4 / 17 | Keep; **bump Java 17 → 21 LTS** |
| Persistence | PostgreSQL + JPA + Liquibase | — | **Keep** |
| Object storage | spring-cloud-aws S3 → Cloudflare R2 | 4.0.2 | **Keep** |
| Job orchestration | Google Cloud Run Jobs | google-cloud-run 0.92 | **Rework** (see R2) |
| Auth | auth0 java-jwt + Spring Security | 4.5.2 | Keep; **fix secret management** |
| API contract | OpenAPI 3 (1 602 lines) → codegen both sides | — | **Keep — this is a genuine asset** |
| OMR | homr (ML) | git HEAD | Keep as engine #1; **add a second engine** |
| Symbolic tooling | MuseScore3 CLI, music21, partitura(absent), pretty_midi | — | Keep; formalize |
| Fingering | pianoplayer | git HEAD | Keep |
| Difficulty | piano-syllabus-classifier (0–8, global) | git HEAD | Keep as global grade; **insufficient alone** |

---

## 3. Reusable assets (keep / harden)

### 3.1 Tier A — keep essentially as-is

| Asset | Path | LOC | Why it survives the pivot |
|---|---|---|---|
| OpenAPI contract | `openapi/api.yaml` | 1 602 | Single source of truth generating both the Angular client and the Spring interfaces. Extend it; never hand-write clients. |
| Liquibase changelog | `backend/src/main/resources/db/changelog/` | 21 changesets | Working, ordered, additive schema history. Add to it. |
| Score/Author/Genre domain | `backend/.../entity/`, `service/ScoreService` | ~900 | Catalog, slugs, ownership, search, genre tree, MusicBrainz enrichment — all still needed. |
| Auth & security | `security/JwtAuthenticationFilter`, `JwtTokenProvider`, `config/SecurityConfig` | ~205 | Structurally fine. Only the secret handling is broken. |
| S3/R2 storage layer | `PackService`, `ScoreService.getAttachmentFromScore` | — | Bucket-key convention and ZIP-per-score packaging work. |
| Audio engine | `desktop/service/player-audio.service.ts` | 494 | Tone.js transport + SpessaSynth SoundFont + per-track channel/program mapping + metronome. Non-trivial and correct. |
| MIDI input service | `shared/services/midi-service.service.ts` | 465 | WebMIDI device discovery, note-on/off stream, keyboard highlighting. |
| Assessment core | `desktop/service/player-assess.service.ts` | 242 | Expectation sets, early/late buckets, `GOOD_RANGE`/`PERFECT_RANGE`/`QUANT_RANGE` windows, `shouldPause` gating. **This is the seed of the feedback engine.** |
| Music theory utils | `desktop/service/music-theory.ts`, `midi-maths.ts`, `key-detection.service.ts`, `model/reduced-fraction.ts` | ~700 | Key spellings, clef detection, exact-fraction tick math. Pure functions, easy to unit-test. |
| OMR container recipe | `backend/Dockerfile` | 100 | Reproducible install of homr + relieur + MuseScore3 + pianoplayer + music21 + CPU torch. Weeks of work encoded here. |
| Score fixtures | `Scores/*.pdf` + matching `.mp3` | 10 pairs | Excellent OMR regression corpus — **but see R5 on licensing.** |

### 3.2 Tier B — keep the algorithm, move or rewrite the host

| Asset | LOC | Action |
|---|---|---|
| `cursor.service.ts` — OSMD-cursor ↔ MIDI-tick alignment, repeat/volta/D.C. unrolling, `OsmdArrayElement` index | 1 101 | **Extract the algorithm to the server.** Its output (the aligned index) becomes a precomputed field of the `ScoreDocument`. The client keeps only a lookup. |
| `smith-waterman.ts` — local sequence alignment, two variants (`sw1`/`sw2`) | 489 | **Port to the analysis worker.** Pure, dependency-free, testable — the single most portable file in the repo. |
| `player.service.ts` — scheduling, wait-for-hand gating, bad-note highlighting | 576 | **Split.** Scheduling/transport stays client-side; the "should I pause" policy moves into a stage-aware `PracticePolicy`. |
| `exercises/` — scale/chord/arpeggio generator producing MusicXML + MIDI in-browser | ~1 800 | **Keep as a feature**, but it must feed the same `ScoreDocument` schema as PDF-ingested pieces, not `localStorage` blobs. |
| `import/` — upload + track selection + hand split UI | ~600 | Keep the flow; **rebuild the UI** around the new async job status model. |
| Python analysis scripts (`extract_*.py`, `get_metadata.py`, `has_*.py`) | ~600 | **Keep the logic, drop the shell orchestration.** These become functions in a typed worker service. |

### 3.3 Tier C — reference only

`home/components/homegl` (459 LOC WebGL shader landing page), `blog/`, SEO service, sitemap
generators. Product-marketing surface, unaffected by the pivot. Leave alone.

---

## 4. Discardable assets

| Asset | LOC | Evidence | Action |
|---|---|---|---|
| `desktop/service/engraving.service.ts` | 405 | Referenced **only** by `animated-score.component.ts` | **Delete** |
| `desktop/service/hand-detector.service.ts` | 475 | Referenced **only** by `engraving.service.ts` | **Delete** — but first port the chord-splitting heuristic (§6.3) |
| `desktop/service/rest-filler.ts` | ~60 | Only used by `engraving.service.ts` | **Delete** |
| `desktop/components/animated-score/` | 99 + tpl | Not referenced by any route, module or template | **Delete** |
| `desktop/components/pianoman/` | ~40 | Zero references | **Delete** |
| `desktop/components/svg-icon/` | ~30 | Zero references, superseded by `@ng-icons` | **Delete** |
| `vexflow` direct dependency | — | Only remaining import is a *type-only* import in `model/model.ts` | **Remove from `package.json`** once the above are gone |
| `nouislider` + `wnumb` + `@types/wnumb` | — | One usage (measure-range slider in the workbench, which is being replaced) | **Drop** |
| `axios` | — | Angular `HttpClient` is used everywhere; axios has no call site in `src/app` | **Verify then drop** |
| `@criblinc/docker-names` | — | Random-name generator; cosmetic | Drop |
| `backend/scripts/midi2pack1.sh`, `test.sh`, `convert.py` | ~200 | Duplicated/ad-hoc variants of `midi2pack.sh` | **Delete** |
| 31 frontend `.spec.ts` files | 852 total | 21 of them are 23-line `should create` stubs | **Delete the stubs**, keep `musicbrainz`, `loading`, `link` specs |
| `frontend/src/sitemap.xml` + `public/sitemap.xml` | — | Two copies, one generator script | Consolidate |

**Total identified dead/near-dead frontend code: ~1 200 LOC + 4 npm dependencies.**

---

## 5. Legacy debt, anti-patterns and defects

### R1 — Hardcoded JWT secret (Critical, security)

`backend/src/main/resources/application.properties:14`

```properties
jwt.secret=your-super-secret-key-that-is-long-enough
```

A committed default signing key. If any deployment inherits it, every token is forgeable.
Also present: the Cloudflare R2 account-scoped endpoint URL.

**Fix:** remove the default entirely; require `JWT_SECRET` from the environment and fail fast at
startup if absent (`@ConfigurationProperties` + `@NotBlank` + `@Validated`). Rotate the key.
Move R2 endpoint/bucket to env. Add a secret scanner to CI.

### R2 — The OMR worker contract is broken (High, correctness)

Three compounding problems in the job path:

1. **Arguments are dropped.** `CloudRunJobService.executeJob(scoreId, s3Key)` builds
   `RunJobRequest.newBuilder().setName(jobName)` and never attaches overrides — the `scoreId`
   and `s3Key` it was given are used only for logging.
2. **The worker compensates by draining everything.** `WorkloadProcessingService.processAllWorkloads()`
   loads *all* `PENDING` workloads and processes them in one execution, so two concurrent triggers
   process the same rows twice. There is no claim/lease, no idempotency key, no `@Transactional`
   status transition.
3. **`System.exit(0)` inside a `@Service`.** Called on both the empty and the completed path —
   untestable, and it bypasses Spring's shutdown hooks.

Additional: `triggerCloudRunJob` catches the failure inside a `whenComplete` callback on a
`CompletableFuture`, so a synchronous caller sees success regardless. On the empty-list path the
method returns after `System.exit(0)` — unreachable code.

**Fix:** move to a real queue (Cloud Tasks or Pub/Sub) with one message per workload, an atomic
`PENDING → RUNNING` claim (`UPDATE ... WHERE status='PENDING' RETURNING`), a visibility timeout,
a retry budget and a dead-letter path. See `DATA_PIPELINE.md` §5.

### R3 — No meaningful test coverage (High)

- Backend: 17 test classes / 2 622 LOC — genuinely useful, concentrated on `ScoreController` and
  `ScoreService`. **`WorkloadProcessingService`, `CloudRunJobService` and the pack scripts have zero tests.**
- Frontend: 31 spec files / **852 LOC total**, of which 21 are `should create` stubs.
  `cursor.service` (1 101), `player.service` (576), `player-assess.service` (242) and
  `smith-waterman.ts` (489) — the entire correctness-critical core — have **no tests at all**.
- `@playwright/test` is installed. There is no `playwright.config`, no `e2e/` directory, no test.
- No `.github/`, no CI configuration anywhere in the tree.

### R4 — The client-side alignment engine (High, maintainability)

`cursor.service.ts` runs, in the browser, on every score load:

- a first pass walking the OSMD cursor to build `osmdMeasureToFirstStepIndex`,
- a second pass building `OsmdArrayElement[]` capped at `maxSecondPassIterations`,
- repeat/volta/D.C. unrolling in `buildOsmdMeasureSequence()` guarded by `security < 10000` and `MAX_DACAPO`,
- Smith-Waterman alignment against the MIDI tick stream,
- `hydrateOsmdArray(lookahead = 6)` with a pitch-overlap heuristic,
- periodic `await`/yield calls (`UI_YIELD_STEP`) so the main thread survives.

Magic iteration caps as loop guards mean malformed input degrades silently into a wrong cursor
rather than a reported error — the single worst property for a product whose input is OMR output.
`verify()` computes a quality score but nothing acts on it.

**Fix:** this computation is deterministic per score. Run it **once, server-side**, at ingestion;
persist the result; ship it to the client as a static index. Client-side cost drops to an
`O(1)` map lookup, and the alignment becomes unit-testable against the `Scores/` corpus.

### R5 — Content licensing (High, legal)

`Scores/` contains 10 PDFs including *Interstellar Theme* (Hans Zimmer), *Mia & Sebastian's Theme*
(Justin Hurwitz), *Howl's Moving Castle* (Joe Hisaishi), *Je Te Laisserai Des Mots* (Patrick Watson)
— all in copyright — plus matching MP3 renditions. Meanwhile `Score.publicDomain` defaults to
`true` and `ScoreService.createScore` sets "EU public domain status if possible" heuristically.

**Fix:** move the corpus out of the repo into a private, gitignored fixtures bucket used only for
OMR regression. Flip the `publicDomain` default to `false`. Add an explicit rights field
(`PUBLIC_DOMAIN | LICENSED | USER_UPLOAD_PRIVATE`) and gate sharing/publishing on it. User uploads
must default to private-to-owner.

### R6 — No version control, no CI (High, process)

The working tree is not a git repository. There is no history, no branch protection, no automated
build, no dependency scanning. Phase 1 cannot start without this.

### R7 — OMR has no quality gate (High, product)

`pdf2pack.sh` rasterises at 300 dpi, runs `homr` per page, and treats *any* successful page as
success (`exit 1` only if **all** pages fail). Failed pages are silently dropped from the
`relieur` concat, so a 12-page piece can silently become a 9-page score. There is no confidence
score, no per-measure validation, and no user-facing correction step. A learning roadmap built on
a mis-recognised score teaches the wrong notes.

### Secondary defects and code smells

| ID | Location | Issue |
|---|---|---|
| S1 | `backend/scripts/pdf2pack.sh:10-13` | Arity check prints usage but `exit 1` is **commented out** — runs with missing args |
| S2 | `backend/scripts/pdf2pack.sh:61` | Stray `n start` line (node version manager leftover) in the middle of the pipeline |
| S3 | `backend/scripts/pdf2pack.sh:27,67,116` | Unquoted `$FILES`/`$XMLFILES`/`$FROOT` globs — breaks on the spaces already present in `Scores/` filenames |
| S4 | `PackService.runPackScript:200` | Passes `getTrackRight()`/`getTrackLeft()` straight to `ProcessBuilder` — a `null` throws `NullPointerException` |
| S5 | `PackService.runPackScript:213` | `path.replace("upload_","").split("\\.")[0] + ".zip"` — silently wrong for any temp path containing a dot |
| S6 | `PackService.packMidi:77-81` | `if/else` branches are **identical** |
| S7 | `WorkloadProcessingService:148` | Error message always says `ori.pdf` even when the missing entry is `ori.png` |
| S8 | `PackService` | Field injection (`@Autowired` on fields) instead of constructor injection — inconsistent with `WorkloadProcessingService` in the same package |
| S9 | `PlayerStateService` | Mutable public-field god object; `tick: any`; commented-out state; a hand-rolled memo cache with `TODO: duplicate of currentTick?` |
| S10 | `workbench.component.ts` | 1 009 LOC component: routing, storage, MIDI, slider, fullscreen, tour, playback, telemetry |
| S11 | `browse.component.ts` | 1 011 LOC component |
| S12 | `osmd.component.ts:113-139` | Readiness detected by polling `setTimeout(100)` up to 20×, then a 1 s `setTimeout` that nudges the cursor `next()/previous()` |
| S13 | `osmd.component.ts:160` | `document.getElementById('cursorImg-0')` + `style.setProperty(..., 'important')` — DOM reach-around into OSMD internals |
| S14 | `import-work.component.ts` | `work: any`, `(error as any)?.error?.error` triple-any error unwrapping |
| S15 | Repo-wide | Mixed French/English identifiers, comments and UI strings; no i18n framework |
| S16 | `biome.json` | 8 lines, one rule disabled — effectively unconfigured |
| S17 | `frontend/package.json` | `@types/*` packages listed under `dependencies` instead of `devDependencies` |
| S18 | `application.properties` | Points at `localhost:5432` as the committed default datasource |
| S19 | `Score.java` | `@Data` on a JPA entity (Lombok `equals`/`hashCode` over a lazy `@ManyToOne` graph); `harmony`/`youtubeLinks` typed as `String` holding JSON |
| S20 | `pom.xml` | Both `jakarta.annotation-api` **and** `javax.annotation-api`; a hand-pinned `gax` version working around a BOM conflict |

---

## 6. Cleanup strategy

### 6.1 Ordering principle

Do not refactor and re-architect at once. Three sequential gates:

1. **Gate 0 — Make change safe.** Git + CI + secrets + a characterization test suite over the
   existing OMR pipeline using the `Scores/` corpus. No behaviour changes.
2. **Gate 1 — Delete.** Remove dead code and dependencies. Verified by build + the Gate 0 suite.
3. **Gate 2 — Extract.** Move the alignment/analysis computation server-side behind the existing
   OpenAPI contract, so the client can be simplified without a coordinated big-bang release.

### 6.2 Gate 0 checklist (blocking)

- [ ] `git init`, `.gitignore` (node_modules, target, dist, `Scores/`, `*.local.properties`), initial commit
- [ ] Remove `jwt.secret` default → env var, fail-fast validation, rotate key
- [ ] Move `Scores/` to a private fixtures bucket; keep a manifest + checksums in-repo
- [ ] CI: `mvn verify` + `ng build` + `biome ci` + secret scan (gitleaks) on every push
- [ ] Golden-file harness: for each of the 10 corpus PDFs, run `pdf2pack.sh` and snapshot
      `metadata.json` + measure count + note count. This is what protects every later change.
- [ ] Pin the git-cloned OMR dependencies (homr, relieur, pianoplayer, autoharmonizer) to commit
      SHAs in the Dockerfile — currently `--depth=1` on default branch, so the image is not reproducible

### 6.3 Gate 1 — deletions (in order)

1. Port the chord/hand-splitting heuristic out of `hand-detector.service.ts` into
   `packages/score-analysis` (it encodes real musical knowledge: onset grouping, split-point
   penalties, hand-span constraints) **before** deleting the file.
2. Delete `animated-score/`, `pianoman/`, `svg-icon/`, `engraving.service.ts`,
   `hand-detector.service.ts`, `rest-filler.ts`.
3. Drop `vexflow`, `nouislider`, `wnumb`, `@types/wnumb`, `@criblinc/docker-names`, `axios`
   (after verifying zero call sites).
4. Delete `midi2pack1.sh`, `test.sh`, `convert.py`.
5. Delete the 21 stub spec files.
6. Move `@types/*` to `devDependencies`.

### 6.4 Gate 2 — extractions

| Move | From | To |
|---|---|---|
| Smith-Waterman alignment | `frontend/.../smith-waterman.ts` | analysis worker (Python or a shared TS package) |
| OSMD/MIDI index construction | `cursor.service.ts` passes 1–3 | ingestion pipeline → `ScoreDocument.timeline` |
| Repeat/volta/D.C. unrolling | `cursor.service.buildOsmdMeasureSequence` | ingestion pipeline → `ScoreDocument.playbackOrder` |
| Hand assignment | `hand-detector.service.ts` | ingestion pipeline → `ScoreDocument.measures[].hands` |
| Key/clef detection | `key-detection.service.ts` | ingestion (music21 does this better) |

After Gate 2, `cursor.service.ts` should be under ~150 lines: fetch the index, look up the current
step, move the OSMD cursor.

### 6.5 Component decomposition targets

| Component | Now | Target |
|---|---|---|
| `workbench.component.ts` | 1 009 | Shell ≤ 150 + `PracticeStageHost`, `TransportBar`, `ScoreViewport`, `PracticeHud`, `SessionSummary` |
| `browse.component.ts` | 1 011 | `BrowseShell` + `FilterPanel` + `ScoreGrid` + a `browse-filters` signal store |
| `cursor.service.ts` | 1 101 | `ScoreIndexService` (≤ 150) |
| `player.service.ts` | 576 | `TransportService` + `PracticePolicyService` |

House rule going forward: **components ≤ 300 LOC, services ≤ 400 LOC, files ≤ 800 LOC hard cap.**

### 6.6 State management upgrade

Replace `PlayerStateService`'s mutable public fields with domain signal stores exposing readonly
signals and explicit intent methods:

```
ScoreStore      score document, load status, render status
TransportStore  playing, position, tempoFactor, loop range
PracticeStore   active plan, active stage, chunk, attempt counters
FeedbackStore   live expectations, hits/misses, rolling accuracy
DeviceStore     MIDI input/output devices, latency calibration
```

Rules: no `any`; state is replaced, never mutated in place; derived values use `computed()`;
`effect()` only for genuine side effects. Migrate to **zoneless** change detection once the
workbench is decomposed.

### 6.7 What *not* to do

- **Do not rewrite the frontend in React.** Angular 21 with standalone components and signals is
  current and capable; a rewrite buys nothing and costs the whole practice engine.
- **Do not replace Spring Boot.** The backend is small, conventional and tested.
- **Do not replace OSMD.** No web alternative offers MusicXML rendering *with* a cursor and
  fingering/lyric control. (Verovio renders better but has no cursor model.)
- **Do not hand-write API clients.** Extend `openapi/api.yaml` and regenerate.

---

## 7. Effort estimate for cleanup

| Gate | Work | Estimate |
|---|---|---|
| Gate 0 | git/CI/secrets/fixtures/golden harness | 5–8 days |
| Gate 1 | deletions + dependency pruning | 2–3 days |
| Gate 2 | server-side extraction of alignment + analysis | 10–15 days |
| Decomposition | workbench + browse + state stores | 8–12 days |
| Test backfill | cursor/alignment/assessment unit tests + 5 Playwright journeys | 8–10 days |
| **Total** | | **~7–10 weeks of focused work** |

This maps to Phase 1 and part of Phase 2 in `IMPLEMENTATION_ROADMAP.md`.

---

## 8. Open questions for the product owner

1. **Multi-tenancy of uploads.** Should a user's uploaded PDF stay private by default, or join the
   shared library? (Affects R5 and the storage key scheme.)
2. **MIDI hardware as a requirement.** The assessment engine assumes a MIDI keyboard. Is
   microphone/audio-pitch-detection input in scope as a fallback? It changes the feedback architecture.
3. **Do the `exercises/` scale & arpeggio generators remain a first-class feature**, or become
   auto-generated warm-up stages derived from the uploaded piece's key?
4. **OMR correction UX.** Is a human-in-the-loop correction step acceptable in the v1 flow, or must
   ingestion be fully automatic? (Drives the Phase 2 scope materially.)
5. **Language.** Codebase and UI mix French and English. Pick one, or adopt `@angular/localize` now.
