<div align="center">

# PiLearn

**Turn a sheet-music PDF into a practice plan you can actually follow.**

Upload a score. PiLearn recognises it, analyses its difficulty measure by measure, and generates
a progressive roadmap — hands separate, phrase by phrase, tempo ramping — then listens to your
MIDI keyboard and adapts as you play.

</div>

---

## What it does

A pianist with a PDF of a piece they want to learn opens it, plays badly from bar 1, gets stuck
at bar 34, and loses momentum. PiLearn turns that PDF into a **plan**: what to practise, in what
order, at what tempo, with which hand, until which criterion is met.

```
  ┌────────┐   ┌───────────┐   ┌──────────────┐   ┌──────────┐   ┌────────────┐
  │  PDF   │──►│ Multi-    │──►│  Canonical   │──►│ Practice │──►│ Assessment │
  │ upload │   │ engine    │   │ ScoreDocument│   │ Roadmap  │   │  Engine    │
  └────────┘   │ OMR       │   └──────────────┘   └──────────┘   └────────────┘
               │ homr +    │    measures, hands,   chunks,        live MIDI,
               │ Audiveris │    key/time, tempo,   stages,        note-level
               │ arbiter   │    alignment index,   tempo ramp,    feedback,
               └───────────┘    difficulty         mastery gates  adaptation
```

**Core capabilities**

- **PDF sheet ingestion** — 300 dpi rasterisation → OMR → MusicXML → normalised MIDI, with a
  per-measure confidence report and a human review gate when recognition is uncertain
- **Learning pathway generator** — phrase-aware chunking, hands-separate → hands-together
  ladders, difficulty-derived starting tempo, mastery criteria, cascading join stages
- **Interactive practice** — engraved score with a tracking cursor, falling-notes view, virtual
  keyboard, wait-for-hand mode, metronome, chunk looping
- **Live assessment** — note-level correct / early / late / wrong / missed, rushing-and-dragging
  detection, per-measure error attribution
- **Adaptive re-planning** — tempo step-downs, chunk splitting, extra hands-separate work for a
  lagging hand, spaced review of mastered material

Also carried forward: a browsable score library with MusicBrainz enrichment, automatic fingering
generation, harmonic analysis, and a scale/chord/arpeggio exercise generator.

---

## Architecture

```
┌───────────────────────────────────────────────────────────────────────────┐
│  Frontend — Angular 21 (standalone, signals, zoneless), SSR via Express   │
│                                                                           │
│  Surfaces   library · upload · review · plan · practice · progress        │
│  Engines    OpenSheetMusicDisplay (engraving + cursor)                    │
│             Tone.js (transport) · SpessaSynth (SoundFont synthesis)       │
│             WebMIDI (keyboard I/O) · canvas piano roll                    │
│  State      per-domain signal stores; no NgRx                             │
│  API client generated from openapi/api.yaml                               │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │ REST + SSE
┌──────────────────────────────┴────────────────────────────────────────────┐
│  Backend — Spring Boot 3.5 / Java 21                                      │
│                                                                           │
│  catalog     scores, authors, genres, MusicBrainz enrichment              │
│  ingestion   job queue, confidence gate, storage                          │
│  learning    chunking · stage ladder · adaptation      ← the Pedagogy Engine
│  practice    sessions · attempts · progress · mastery                     │
│  security    JWT auth, ownership and rights enforcement                   │
└──────┬──────────────────────────────────────────┬─────────────────────────┘
       │ PostgreSQL 16                            │ queue
       │  catalog + score_document +              │
       │  learning_plan + attempts +              ▼
       │  measure_result + chunk_mastery    ┌────────────────────────────────┐
       ▼                                    │  OMR Worker — FastAPI / Python │
  S3-compatible object storage              │                                │
   MinIO locally · Cloudflare R2 in prod    │  pdftoppm → homr | Audiveris   │
   raw/     immutable originals             │  → relieur merge               │
   derived/ musicxml, midi, document.json,  │  → MuseScore3 normalise        │
            index.json, confidence.json     │  → music21 / partitura analyse │
                                            │  → pianoplayer fingering       │
                                            │  → difficulty + alignment      │
                                            └────────────────────────────────┘
```

### Data flow

**PDF → Multi-engine OMR → `ScoreDocument` → Practice Roadmap → Assessment Engine**

The `ScoreDocument` is the canonical representation: measures, notes with hand assignment,
key/time signatures, tempo map, resolved playback order (repeats and voltas unrolled), a
precomputed notation↔MIDI **alignment index**, and per-measure difficulty vectors. Everything
downstream — engraving, playback, planning, scoring — reads from it, so no surface re-parses
the score and no two surfaces can disagree.

Alignment is computed **once, server-side, at ingestion**. The client does an `O(1)` lookup per
cursor advance.

Full detail: [`docs/DATA_PIPELINE.md`](docs/DATA_PIPELINE.md).

### Tech stack

| Layer | Technology |
|---|---|
| Frontend | Angular 21 · TypeScript 5.9 · Tailwind 4 · Vite |
| Engraving | OpenSheetMusicDisplay 1.9 |
| Audio | Tone.js 15 · spessasynth_lib 4 (SoundFont, AudioWorklet) |
| MIDI | `@tonejs/midi` · WebMIDI |
| Backend | Spring Boot 3.5 · Java 21 · JPA · Liquibase |
| Database | PostgreSQL 16 |
| Storage | S3-compatible (MinIO local · Cloudflare R2 prod) |
| OMR worker | Python 3.11 · FastAPI · homr · Audiveris · MuseScore 3 |
| Analysis | music21 · partitura · pianoplayer · piano-syllabus-classifier |
| API contract | OpenAPI 3 → generated client and server interfaces |
| Testing | Vitest · Playwright · JUnit 5 · Testcontainers |

---

## Quickstart

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Docker | 24+ with Compose v2 | Postgres, MinIO, OMR toolchain |
| Node.js | 22 LTS | frontend |
| Java | 21 (Temurin) | backend |
| Maven | 3.9+ | backend build |
| Python | 3.11 | worker and harness tooling |
| `pdfinfo` | poppler-utils | optional, for fixture inspection on the host |

### 1. Configure

```bash
git clone <repo-url> PiLearn && cd PiLearn
cp .env.example .env
```

Fill in `.env`. At minimum:

```bash
# Required — the app refuses to start without a real signing key.
openssl rand -base64 64 | tr -d '\n'     # paste into JWT_SECRET

DB_PASSWORD=<choose one>
STORAGE_ACCESS_KEY=<choose one>
STORAGE_SECRET_KEY=<choose one, 8+ chars>
```

Every key is documented in [`.env.example`](.env.example). `.env` is gitignored — never commit
a filled-in copy.

### 2. Start everything

```bash
tools/dev-up.sh
```

Checks prerequisites and ports, brings up Postgres, MinIO (with its bucket), Redis and the
OMR worker, starts the backend and the Angular dev server, then prints every URL and log
command. `tools/dev-down.sh` stops it all again.

| Flag | Effect |
|---|---|
| `--infra-only` | containers only — run the backend and frontend yourself (steps 3 and 4) |
| `--no-worker` | skip the 6 GB OMR image; uploads fail, everything else works |
| `--backend-in-docker` | run Spring in a container instead of on the host |

Already running Postgres on 5432? Set `DB_PORT` in `.env` (and match `DB_URL`) instead of
stopping it. `MINIO_PORT`, `MINIO_CONSOLE_PORT`, `REDIS_PORT`, `WORKER_PORT` and
`BACKEND_PORT` work the same way.

Steps 3–5 below are the manual equivalents, for when you want one piece at a time.

### 2b. Start infrastructure only

```bash
docker compose up -d          # postgres + minio + bucket creation
docker compose ps             # both should report healthy
```

- MinIO console → http://localhost:9001
- Postgres → `localhost:5432`, database `pianoml`

### 3. Run the backend

```bash
cd backend
set -a && source ../.env && set +a
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Liquibase applies the schema on first boot.

- API → http://localhost:8080
- Swagger UI → http://localhost:8080/swagger-ui.html

### 4. Run the frontend

```bash
cd frontend
npm ci
npm start                     # generates the API client, then serves on :4200
```

Open http://localhost:4200. The dev server proxies `/api` to the backend per
`proxy.conf.json`; `npm run start-remote` targets the hosted API instead.

### 5. (Optional) OMR toolchain

Only needed when working on ingestion or running the baseline harness. The first build pulls
CPU-only PyTorch, MuseScore 3 and homr — **20–40 minutes, ~6 GB**.

```bash
docker compose --profile omr up -d --build
```

### 6. Check it actually works

Compiling is not the same as working. Walk the upload → practice path in a real browser
using [`docs/E2E_SMOKE_TEST.md`](docs/E2E_SMOKE_TEST.md). No account is needed — anonymous
uploads are attached to a guest session.

---

## Common tasks

```bash
# Frontend
npm start                     # dev server (regenerates the API client first)
npm test                      # unit tests
npm run build:prod            # production build
npx playwright test           # e2e

# Backend
mvn verify                    # compile + test + JaCoCo coverage
mvn test -Dtest=ScoreServiceTest
mvn spring-boot:run -Dspring-boot.run.profiles=local

# API contract — edit openapi/api.yaml, then regenerate BOTH sides
cd frontend && npm run generate:api
cd backend  && mvn generate-sources

# OMR baseline harness
./tools/omr-baseline/run-baseline.sh --check
```

> **Never hand-edit the generated API client** (`frontend/src/app/core/api/`). Change
> `openapi/api.yaml` and regenerate. It is gitignored for exactly this reason.

---

## Project layout

```
PiLearn/
├── frontend/            Angular 21 SPA + SSR
│   └── src/app/
│       ├── desktop/     practice surface — player, cursor, keyboard, OSMD
│       ├── library/     score browsing and detail
│       ├── import/      upload and ingestion flow
│       ├── exercises/   scale / chord / arpeggio generator
│       ├── account/     auth and ownership
│       └── shared/      cross-cutting services and components
├── backend/             Spring Boot API
│   ├── src/main/java/org/pianoml/backend/
│   │   ├── controller/  REST endpoints (OpenAPI-generated interfaces)
│   │   ├── service/     domain logic
│   │   ├── repository/  JPA + custom queries
│   │   ├── entity/      persistence model
│   │   └── security/    JWT
│   ├── src/main/resources/db/changelog/   Liquibase
│   ├── scripts/         legacy OMR shell pipeline (being replaced in Phase 2)
│   └── Dockerfile       OMR toolchain image
├── openapi/api.yaml     the API contract — single source of truth
├── tools/
│   ├── local/           local infra bootstrap
│   └── omr-baseline/    OMR regression + accuracy harness
├── docs/                architecture and planning
└── docker-compose.yml   local development stack
```

---

## Documentation

| Document | What it covers |
|---|---|
| [`docs/AUDIT_AND_REFACTOR.md`](docs/AUDIT_AND_REFACTOR.md) | Codebase audit: what survives the pivot, what gets deleted, every known defect, and the ordered cleanup strategy |
| [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md) | Target features, user flows, the learning model (chunking, stage ladders, adaptation), system architecture, and every technology decision with its rationale |
| [`docs/DATA_PIPELINE.md`](docs/DATA_PIPELINE.md) | The P0–P9 ingestion stages, the `ScoreDocument` schema, job orchestration, persistence model, and pipeline testing strategy |
| [`docs/IMPLEMENTATION_ROADMAP.md`](docs/IMPLEMENTATION_ROADMAP.md) | Five phases, ~130 tasks with IDs, estimates, definitions of done, dependency graph, and schedule risks |
| [`docs/PHASE0_SETUP.md`](docs/PHASE0_SETUP.md) | Repository lockdown runbook: gitignore, secret externalization, git initialization |
| [`tools/omr-baseline/README.md`](tools/omr-baseline/README.md) | How to measure OMR quality before and after the Phase 2 migration |

---

## Project status

PiLearn is a pivot of **PianoML**, a working piano-practice application. The ingestion pipeline,
score renderer, playback engine and MIDI assessment core are inherited and functional. The
Pedagogy & Roadmap Engine is being built on top.

| Phase | Scope | Status |
|---|---|---|
| **0** | Repository lockdown, secret externalization, baseline harness | **in progress** |
| **1** | Teardown: dead-code removal, CI, test infrastructure | planned |
| **2** | Typed ingestion worker, `ScoreDocument`, confidence gate | planned |
| **3** | Pedagogy engine: chunking, stages, adaptation, telemetry | planned |
| **4** | Practice UI rebuild, falling notes, review gate | planned |
| **5** | Accessibility, performance, migration, launch hardening | planned |

Track progress against the task IDs in
[`docs/IMPLEMENTATION_ROADMAP.md`](docs/IMPLEMENTATION_ROADMAP.md).

---

## Contributing

- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org/):
  `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`
- **Tests first.** Coverage floor is 80 % on ingestion, learning, alignment and difficulty modules.
- **Size limits.** Components ≤ 300 LOC, services ≤ 400 LOC, files ≤ 800 LOC hard cap.
- **No secrets in source**, ever. CI fails the build on a detected secret.
- **No `any`.** State is replaced, never mutated in place.
- Changing the API means editing `openapi/api.yaml` and regenerating both sides.

---

## Browser support

The practice surface needs **WebMIDI**, which is Chromium-only today.

| Browser | Playback & follow-along | MIDI input & scoring |
|---|---|---|
| Chrome / Edge / Brave 120+ | yes | yes |
| Firefox | yes | no |
| Safari | yes | no |

Non-Chromium browsers get a degraded mode: score display, playback and cursor tracking work;
scoring is disabled with an explicit notice.

---

## License

See [`frontend/LICENSE.md`](frontend/LICENSE.md).

Sheet music you upload remains yours. Uploaded scores are **private to your account by default**
and are never published without an explicit rights declaration.
