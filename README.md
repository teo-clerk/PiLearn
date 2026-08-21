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

## The idea in one minute

PiLearn turns a PDF into a **roadmap**: an ordered list of small things to practise, built
around how much piano you already have.

Four words are worth knowing, because the whole app is made of them:

| Term | What it means |
|---|---|
| **Document** | What your PDF becomes: every note, its bar, which hand plays it, how hard that bar is, and how notation lines up with clock time. Everything else is built from this. |
| **Chunk** | A slice of the piece you practise as one unit. One bar for a complete beginner, a four-bar phrase for someone who already plays. |
| **Stage** | One objective within a chunk — *"right hand, no rush"*, *"both hands at 60%"*. Stages have a pass mark, so the app knows when you have got it. |
| **Roadmap** | Every chunk and its stages, in order. Your plan for the piece. |

So an 11-bar Chopin prelude becomes **13 chunks and 104 stages** for someone who has never
played, and **2 chunks and 11 stages** for someone who has. Same piece, same document —
different plan, because the two learners are stuck on completely different things.

---

## Setup

### 1. What you need

| | Needed for | Notes |
|---|---|---|
| **Docker** + Compose plugin | everything | The one hard requirement. `docker info` must work. |
| **~8 GB free disk** | the OMR engine | The recognition image is ~5.4 GB, the backend ~840 MB. |
| **Node 20+** | the web app | Always, unless you use `--infra-only`. The web app runs on your machine. |
| **JDK 21** | the backend | Only without `--backend-in-docker`, which runs it in a container instead. |
| **Python 3.11 or 3.12** | worker tests | Only for running the worker's test suite. The app itself uses the container. |

`tools/dev-up.sh` checks all of this before it starts anything, and tells you exactly what is
missing rather than failing halfway.

### 2. Configure

```bash
cp .env.example .env
```

Then fill in four values — any local value will do for three of them:

```bash
JWT_SECRET=            # openssl rand -base64 64 | tr -d '\n'   — must be 64+ chars
DB_PASSWORD=           # anything
STORAGE_ACCESS_KEY=    # anything
STORAGE_SECRET_KEY=    # anything, 8+ characters
```

Every key is documented in [`.env.example`](.env.example). `.env` is gitignored — never commit
a filled-in copy.

> **Already running Postgres on 5432?** Set `DB_PORT` in `.env` (and match `DB_URL`) instead of
> stopping it. `MINIO_PORT`, `REDIS_PORT`, `WORKER_PORT` and `BACKEND_PORT` work the same way.

### 3. Start

```bash
./tools/dev-up.sh --backend-in-docker
```

This checks your ports and prerequisites, starts Postgres, MinIO, Redis, the recognition worker
and the backend, launches the web app, and prints every URL when it is done.

**The first run builds the recognition image: ~6 GB and 20–40 minutes.** Later runs take about
two minutes, which is mostly the recognition model loading. In a hurry, `--no-worker` skips it —
everything works except uploading new scores.

| URL | What |
|---|---|
| <http://localhost:4200/> | Home — where you drop a PDF |
| <http://localhost:4200/practice/demo> | Demo sandbox — works with no backend at all |
| <http://localhost:4200/library/my-scores> | Your scores and your progress |
| <http://localhost:8080/api/v1> | Backend API |
| <http://localhost:8000/docs> | Worker API (interactive) |
| <http://localhost:9001> | MinIO console — the stored files |

```bash
./tools/dev-down.sh            # stop everything, keep your data
./tools/dev-down.sh --volumes  # ...and delete the database and every upload
```

<details>
<summary><b>Running the pieces separately</b></summary>

```bash
./tools/dev-up.sh --infra-only   # containers only; you run the app yourself

cd backend  && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd frontend && npm ci && npm start
```

`npm start` proxies `/api` to `http://localhost:8080`. Use `npm run start-remote` to develop
against the hosted API instead.

Logs, when something is quiet:

```bash
tail -f .dev/logs/backend.log
tail -f .dev/logs/frontend.log
docker compose -f docker-compose.yml -f docker-compose.dev.yml logs -f worker
```

</details>

---

## Using it

### Try it in thirty seconds

Open <http://localhost:4200/practice/demo>. That is a real practice session on a built-in
score — no upload, no account, no backend. Press <kbd>Space</kbd> and play along on your
computer keyboard. It is the fastest way to see whether any of this is for you.

### The actual flow

**1 · Answer two questions.** On your first visit you are asked how much piano you have played
and whether you read sheet music. That is the whole questionnaire, and it is skippable. Your
answers decide how finely your pieces get sliced and which practice modes you get.

**2 · Drop a PDF on the home page.** Any sheet music. No account needed — anonymous uploads are
tied to a guest session stored in your browser.

> **Guest uploads live in that one browser.** Clearing site data loses them, and signing up does
> not currently adopt them — moving a guest's scores into a new account is built for but not yet
> implemented.

**3 · Wait for it to be read.** Recognition takes anywhere from twenty seconds to a few minutes
depending on length. You will watch it move through *uploading → recognising → analysing →
planning*. When it is done the app takes you straight to the practice surface.

> If a page cannot be read, the score still opens and says so. A partially recognised piece is
> still worth practising; it just tells you which bars to distrust.

**4 · Practise.** The surface shows the score, a keyboard, and a bar naming what this stage is
asking of you. What you get depends on your answers:

- *Never played?* You start by **tapping the rhythm** — any key counts, pitch is ignored. Then
  one bar at a time in **Wait-for-Me**, where nothing moves until you play the right note, with
  the note names drawn on the keys you need.
- *Already play?* You start hands-together on four-bar phrases and spend your time on tempo.

Play with a MIDI keyboard, your computer keyboard, or by clicking the on-screen one. All three
score identically.

**5 · Come back to it.** <http://localhost:4200/library/my-scores> lists everything you have
uploaded with your progress through it. **Resume practice** drops you back on the exact stage and
tempo you left.

### Controls

| Key | Action |
|---|---|
| <kbd>Space</kbd> | Play / pause |
| <kbd>R</kbd> | Restart the current chunk |
| <kbd>Shift</kbd> + <kbd>L</kbd> | Toggle loop |
| <kbd>Shift</kbd> + <kbd>G</kbd> | Toggle the guide track (hands-separate stages only) |

<kbd>L</kbd> and <kbd>G</kbd> need <kbd>Shift</kbd> because both are also piano keys. Without it,
anyone using the computer keyboard as their instrument could never reach the toggles — the note
would swallow the keystroke, silently.

#### Computer keyboard as a piano

Two octaves, laid out the way an online piano is: naturals on the home row, accidentals sitting
above the note they raise.

```text
  W   E       T   Y   U       O   P
  C♯  D♯      F♯  G♯  A♯      C♯  D♯

A   S   D   F   G   H   J   K   L   ;
C   D   E   F   G   A   B   C   D   E

  Z  or  [   octave down          X  or  ]   octave up
```

Starts at C4 (middle C); the range runs C2–C6.

#### On-screen keyboard

Click or touch any key. When the stage calls for it — or you said you cannot read notation — the
pitch names (`C4`, `G3`) are drawn on **only the keys the current step expects**, so the one you
are looking for is not buried under eighty-seven others.

---

## When something goes wrong

| What you see | What it usually is |
|---|---|
| `dev-up.sh` refuses to start, naming a port | Something else holds it. Stop it, or set that port in `.env` (see Setup). |
| The keyboard makes no sound | Your browser blocks audio until you interact with the page. Click the **"Click to enable audio"** banner. |
| Upload says *"simulated"* | The web app cannot reach the backend. Check `tail -f .dev/logs/backend.log`. |
| Upload fails, or the score never leaves *queued* | The recognition worker is not running. Start it, or check `docker compose ... logs -f worker`. |
| A score reaches READY but the page is blank | Keep the status page open a moment — reaching READY is also what pulls the finished score across. If it persists, check the backend log. |
| `/api/...` returns 404 in the browser | The dev proxy is pointing at the hosted API. `proxy.conf.json` should target `http://localhost:8080`. |
| Everything is stale after a code change | `./tools/dev-down.sh && ./tools/dev-up.sh --backend-in-docker` |

For the full walkthrough with checkboxes — what each screen must show, and what it means when it
doesn't — see [`docs/E2E_SMOKE_TEST.md`](docs/E2E_SMOKE_TEST.md).

---

## Working on it

### Tests

```bash
# Frontend
cd frontend
npx tsc --noEmit -p tsconfig.app.json
npx ng build
CHROME_BIN=$(which chromium) npx ng test --watch=false --browsers=ChromeHeadless

# Backend
cd backend
./mvnw -B clean verify

# Worker  (Python 3.11 or 3.12 — the package pins <3.13)
cd worker
python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest tests -q
```

Compiling is not the same as working. Before believing a change, walk the browser checklist in
[`docs/E2E_SMOKE_TEST.md`](docs/E2E_SMOKE_TEST.md).

### Driving the API directly

Useful when you are changing ingestion and do not want to click through the UI:

```bash
SID="guest_$(openssl rand -hex 12)"

SCORE=$(curl -s -F "file=@Scores/Frédéric Chopin - Prelude in E Minor.pdf" \
             -F "title=Prelude in E Minor" -F "composer=Frédéric Chopin" \
             -F "guestSessionId=$SID" \
             http://localhost:8080/api/v1/scores/upload | jq -r .scoreId)

# QUEUED → PROCESSING → READY. Polling is also what pulls the finished score
# across from the worker, so keep polling until it settles.
until curl -s "http://localhost:8080/api/v1/scores/$SCORE/status" \
      | grep -qE '"status":"(READY|REVIEW_REQUIRED|FAILED)"'; do sleep 10; done

curl -s "http://localhost:8080/api/v1/scores/$SCORE/document"       | jq .meta
curl -s "http://localhost:8080/api/v1/scores/$SCORE/document/index" | jq '.steps | length'
curl -s "http://localhost:8080/api/v1/scores/$SCORE/musicxml"       | head -3
curl -s "http://localhost:8080/api/v1/scores/$SCORE/roadmap?skillLevel=BEGINNER_0" | jq .totalStages
```

Swap `skillLevel` between `BEGINNER_0`, `BEGINNER_1`, `INTERMEDIATE` and `ADVANCED` to watch the
same piece produce four different plans.

---

## How it works inside

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
