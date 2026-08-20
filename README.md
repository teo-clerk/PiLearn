<div align="center">

# PiLearn

**Turn a sheet-music PDF into a practice plan you can actually follow.**

Upload a score. PiLearn reads the notes, works out how hard each bar is, and builds a
step-by-step roadmap around *your* level — starting, if you have never played before, with
tapping the rhythm and finding one key at a time.

Then it listens while you play.

</div>

---

## What it is

An adaptive piano-learning assistant. It takes raw sheet music and turns it into an interactive
practice session that meets the learner where they are.

The core claim is the one most piano software skips: **a complete beginner and an intermediate
player do not need the same plan at different speeds — they need different plans.** Someone who
has never touched a piano is not slow at playing the piece; they cannot yet read the notes, find
the keys, or hold a pulse. So they get a different ladder, not a gentler one.

### Practice modes

| Mode | What it does | Who it is for |
|---|---|---|
| **Rhythm tapping** | Any key counts as a hit. Pitch is ignored entirely. | The first thing a total novice can succeed at — one new skill instead of two. |
| **Wait-for-Me** | The transport stops at each note and waits, indefinitely, until you play it. No timer, no deadline. | Anyone still hunting for the keys. A cursor that walks away mid-search turns learning into failure. |
| **Hands separate** | One hand at a time, with the engine playing the other as accompaniment. | Hearing the piece whole before you can play it whole. |
| **Tempo ramp** | 40% → 60% → 80% → 100% for beginners; finer multiplicative rungs for players who can already hold a tempo. | Building speed without losing accuracy. |

### The ladder adapts

Four skill levels, set by a two-question questionnaire on first visit. On the same 11-bar Chopin
prelude:

| Level | Practice units | Stages | Shape of the plan |
|---|---|---|---|
| `BEGINNER_0` | 13 × 1 bar | 104 | Rhythm warm-up → wait-for-me with note names → accompaniment → 40/60/80/100 → whole piece |
| `BEGINNER_1` | 2 bars | 28 | Wait-for-me with note names → 40/60/80/100 |
| `INTERMEDIATE` | 4-bar phrases | 15 | Wait-for-me → in time → fine tempo ramp |
| `ADVANCED` | 4-bar phrases | 11 | Straight to hands together, then tempo |

### Playing it

Three input paths, all equal citizens — the same scoring, the same audio, the same highlighting:

- **WebMIDI** — a real instrument, if you have one
- **QWERTY** — your computer keyboard as a two-octave piano
- **Touch / click** — the on-screen SVG keyboard, with pitch names drawn on the keys you need

---

## How it works

```
   sheet music PDF
         │
         ▼
┌────────────────────┐   homr (OMR) → relieur (merge) → MuseScore
│  FastAPI OMR       │   one long CPU-bound subprocess per job
│  worker  :8000     │   Redis-backed job state, leased and idempotent
└────────┬───────────┘
         │  parse (music21) → neutral IR → build
         ▼
┌───────────────────────────────────────────────────┐
│  Canonical ScoreDocument  +  MusicXML             │
│                                                   │
│  notes · measures · hands · segments · chunks     │
│  alignment index (tick ⇄ second ⇄ cursor step)    │
│  difficulty per bar · confidence per page         │
└────────┬──────────────────────────────────────────┘
         │  JSONB in Postgres, artefacts in S3/MinIO
         ▼
┌────────────────────┐   ChunkPlanner  — what to practise, in what size
│  Spring Boot       │   StageLadder   — what to do with each piece
│  backend  :8080    │   both driven by the learner's skill profile
└────────┬───────────┘
         │  roadmap · document · alignment index · musicxml
         ▼
┌────────────────────────────────────────────────────┐
│  Angular practice surface  :4200                   │
│                                                    │
│  OSMD score  ·  Tone.js transport  ·  WebMIDI in   │
│  SVG keyboard  ·  wait-gate  ·  live scoring       │
└────────────────────────────────────────────────────┘
```

**Why the document sits in the middle.** The worker owns everything that needs music theory —
parsing, phrase detection, hand assignment, difficulty. The backend owns pedagogy. Neither
reaches into the other; they meet at one versioned, immutable JSON document. A revision is never
overwritten, because a learner's saved progress may already point at it.

---

## Quickstart

```bash
cp .env.example .env          # then fill in the REQUIRED values (see below)
./tools/dev-up.sh --backend-in-docker
```

That checks your ports and prerequisites, starts Postgres, MinIO, Redis, the OMR worker and the
backend, brings up the Angular dev server, and prints every URL. First run builds the OMR image
(~6 GB, 20–40 min); later runs take about two minutes, which is the recognition model loading.

| Where | What |
|---|---|
| <http://localhost:4200/> | Home — drag a PDF onto the hero panel |
| <http://localhost:4200/library/my-scores> | Your library, with progress and *Resume practice* |
| <http://localhost:4200/practice/demo> | Demo sandbox — no backend needed |
| <http://localhost:8080/api/v1> | Backend API |
| <http://localhost:8010/docs> | OMR worker API |
| <http://localhost:9001> | MinIO console |

```bash
./tools/dev-down.sh            # stop everything, keep your data
./tools/dev-down.sh --volumes  # ...and destroy the database and all uploads
```

<details>
<summary><b>Required <code>.env</code> values</b></summary>

```bash
JWT_SECRET=            # openssl rand -base64 64 | tr -d '\n'   — min 64 chars
DB_PASSWORD=           # any local value
STORAGE_ACCESS_KEY=    # any local value
STORAGE_SECRET_KEY=    # any local value, 8+ chars
```

Every key is documented in [`.env.example`](.env.example). `.env` is gitignored — never commit a
filled-in copy.

Already running Postgres on 5432? Set `DB_PORT` (and match `DB_URL`) rather than stopping it.
`MINIO_PORT`, `REDIS_PORT`, `WORKER_PORT` and `BACKEND_PORT` work the same way.

</details>

<details>
<summary><b>Running pieces individually</b></summary>

```bash
./tools/dev-up.sh --infra-only   # containers only; run the app yourself
./tools/dev-up.sh --no-worker    # skip the 6 GB OMR image (uploads will fail)

cd backend  && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd frontend && npm ci && npm start
```

`npm start` proxies `/api` to `http://localhost:8080`. Use `npm run start-remote` to develop
against the hosted API instead.

</details>

---

## Controls

### Transport

| Key | Action |
|---|---|
| `Space` | Play / pause |
| `R` | Restart the current chunk |
| `Shift` + `L` | Toggle loop |
| `Shift` + `G` | Toggle the guide track (hands-separate stages only) |

`L` and `G` need **Shift** because both are also piano keys. Without it, a learner using the
computer keyboard as their instrument could never reach the toggles — the note would swallow the
keystroke, silently.

### QWERTY piano

Two octaves, laid out the way an online piano is: naturals on the home row, accidentals sitting
above the note they raise.

```text
  W   E       T   Y   U       O   P
  C♯  D♯      F♯  G♯  A♯      C♯  D♯

A   S   D   F   G   H   J   K   L   ;
C   D   E   F   G   A   B   C   D   E

  Z  or  [   octave down          X  or  ]   octave up
```

Default octave is C4 (middle C); the range runs C2–C6.

### On-screen keyboard

Click or touch any key. When the stage calls for it — or you told us you cannot read notation —
the pitch names (`C4`, `G3`) are drawn on **only the keys the current step expects**, so the one
you are looking for is not buried under eighty-seven others.

---

## Testing

```bash
# Frontend
cd frontend
npx tsc --noEmit -p tsconfig.app.json
npx ng build
CHROME_BIN=$(which chromium) npx ng test --watch=false --browsers=ChromeHeadless

# Backend
cd backend
./mvnw -B clean verify

# Worker  (needs Python 3.11 or 3.12 — the package pins <3.13)
cd worker
python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest tests -q
```

Compiling is not the same as working. For the browser walkthrough — upload a real PDF, watch it
ingest, play it — follow [`docs/E2E_SMOKE_TEST.md`](docs/E2E_SMOKE_TEST.md).

### Ingesting a real score from the command line

```bash
SID="guest_$(openssl rand -hex 12)"

SCORE=$(curl -s -F "file=@Scores/Frédéric Chopin - Prelude in E Minor.pdf" \
             -F "title=Prelude in E Minor" -F "composer=Frédéric Chopin" \
             -F "guestSessionId=$SID" \
             http://localhost:8080/api/v1/scores/upload | jq -r .scoreId)

# QUEUED → PROCESSING → READY. Polling is also what pulls the finished
# document across from the worker, so keep polling until it settles.
until curl -s "http://localhost:8080/api/v1/scores/$SCORE/status" \
      | grep -qE '"status":"(READY|REVIEW_REQUIRED|FAILED)"'; do sleep 10; done

curl -s "http://localhost:8080/api/v1/scores/$SCORE/document"       | jq .meta
curl -s "http://localhost:8080/api/v1/scores/$SCORE/document/index" | jq '.steps | length'
curl -s "http://localhost:8080/api/v1/scores/$SCORE/musicxml"       | head -3
curl -s "http://localhost:8080/api/v1/scores/$SCORE/roadmap?skillLevel=BEGINNER_0" | jq .totalStages
```

No account needed at any point — anonymous uploads are attached to a guest session that lives in
your browser, and signing up later is what makes the library durable.

---

## Project layout

```
backend/     Spring Boot 3.5 · Java 21 · Postgres · Liquibase
  identity/    who is asking (account, or guest session)
  learning/    ChunkPlanner, StageLadderBuilder — the pedagogy engine
  profile/     skill level, notation fluency, preferred input
  library/     the learner's own scores and their progress
  ingestion/   upload, status, and the strangler seam to the worker

worker/      FastAPI · music21 · homr — everything needing music theory
  parser/      MusicXML → neutral IR → canonical ScoreDocument
  pedagogy/    hand detection, per-bar difficulty
  pipeline/    job runner, page accounting, confidence gate

frontend/    Angular 21 standalone · signals · OSMD · Tone.js
  core/        score documents, profile, guest session
  practice/    the practice surface, wait-gate, transport, inputs
  library/     my-scores and the shared catalogue

tools/       dev-up.sh, dev-down.sh, OMR baseline harness
Scores/      local test fixtures (private; not distributed)
```

---

## Documentation

| Document | What it covers |
|---|---|
| [`docs/E2E_SMOKE_TEST.md`](docs/E2E_SMOKE_TEST.md) | The browser walkthrough: upload → ingest → practise, and what each step must show |
| [`docs/LOCAL_DEV_RUNBOOK.md`](docs/LOCAL_DEV_RUNBOOK.md) | Running the stack piece by piece, and what to check when a piece misbehaves |
| [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md) | Target features, user flows, the learning model, and every technology decision with its rationale |
| [`docs/DATA_PIPELINE.md`](docs/DATA_PIPELINE.md) | Ingestion stages, the `ScoreDocument` schema, job orchestration, persistence |
| [`docs/AUDIT_AND_REFACTOR.md`](docs/AUDIT_AND_REFACTOR.md) | Codebase audit: what survives the pivot, what goes, every known defect |
| [`docs/IMPLEMENTATION_ROADMAP.md`](docs/IMPLEMENTATION_ROADMAP.md) | Five phases, ~130 tasks with IDs, estimates and dependencies |
| [`tools/omr-baseline/README.md`](tools/omr-baseline/README.md) | Measuring OMR quality before and after a toolchain bump |

---

## Status

PiLearn is a pivot of **PianoML**, a working piano-practice application.

| Area | State |
|---|---|
| Ingestion — PDF → ScoreDocument + MusicXML | working end to end on real scores |
| Adaptive roadmap — four skill levels | working, verified per level |
| Practice surface — wait-for-me, rhythm, hands separate, tempo ramp | implemented; covered by unit tests |
| Profile, onboarding, my-scores library | working, guest-aware |
| Accounts, sharing, deployment hardening | not started |

**Read this before trusting it:** the practice surface has been driven by tests and by API-level
verification, not yet by a person at a keyboard for a full session. The known gaps are listed at
the top of [`docs/E2E_SMOKE_TEST.md`](docs/E2E_SMOKE_TEST.md). Toolchain pins in
`worker/Dockerfile` move recognition quality when bumped — re-run the baseline harness after any
change there.

---

## Contributing

- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org/).
- **Tests first.** 80% floor on ingestion, learning, alignment and difficulty.
- **Size limits.** Components ≤ 300 LOC, services ≤ 400 LOC, files ≤ 800 LOC hard cap.
- **No secrets in source**, ever.
- **No `any`.** State is replaced, never mutated in place.
- Changing the API means editing `openapi/api.yaml` and regenerating both sides.

---

## Browser support

The practice surface needs **WebMIDI** for instrument input, which is Chromium-only today.
Everything else — including the QWERTY and touch keyboards — works everywhere.

| Browser | Score, playback, QWERTY & touch | MIDI instrument input |
|---|---|---|
| Chrome / Edge / Brave 120+ | yes | yes |
| Firefox | yes | no |
| Safari | yes | no |

Browsers suspend audio until the page has seen a real click, so the practice surface shows a
**Click to enable audio** banner rather than presenting a silent instrument.

---

## License

See [`frontend/LICENSE.md`](frontend/LICENSE.md).

Sheet music you upload remains yours. Uploaded scores are **private by default** and are never
published without an explicit rights declaration.
