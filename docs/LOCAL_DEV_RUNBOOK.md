# LOCAL_DEV_RUNBOOK

Getting the full PiLearn stack running locally and driving the practice surface in a real
browser.

For first-time setup (secrets, Maven wrapper, prerequisites) see
[`PHASE0_SETUP.md`](./PHASE0_SETUP.md). This document assumes that is done.

---

## 0. What you actually need

The practice surface has three dependencies, and you can skip two of them depending on
what you are working on:

| Working on | Needs | Skip |
|---|---|---|
| Practice UI, transport, MIDI | Postgres + backend + a pre-ingested score | OMR worker |
| Ingestion pipeline | Postgres + MinIO + OMR worker | Angular |
| End-to-end upload → practice | everything | — |

The OMR worker image is ~6 GB and takes 20–40 minutes to build the first time. Do not
build it unless you are actually working on ingestion.

---

## 1. Backing services

```bash
cd /path/to/PiLearn

# Postgres + MinIO + bucket creation. ~10 seconds.
docker compose up -d
docker compose ps            # both should report healthy

# Add the OMR worker + Redis only when working on ingestion (slow first build).
docker compose --profile omr up -d --build
```

| Service | URL | Notes |
|---|---|---|
| Postgres | `localhost:5432` | database `pianoml` |
| MinIO API | `localhost:9000` | S3-compatible |
| MinIO console | http://localhost:9001 | log in with `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` |
| OMR worker | http://localhost:8000 | `/health` reports readiness per capability |
| Redis | `localhost:6379` | worker job state |

Check the worker is genuinely ready, not merely up:

```bash
curl -s localhost:8000/health | jq
# status "ok" means MuseScore, poppler and the pipeline scripts are all reachable.
# "degraded" means the container runs but cannot do useful work — read `checks`.
```

---

## 2. Backend

```bash
cd backend
set -a && source ../.env && set +a     # export DB_PASSWORD, JWT_SECRET, STORAGE_*
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Liquibase applies the schema on first boot, including changeset `021` (the
`score_document` table and the score processing columns).

- API → http://localhost:8080
- Swagger → http://localhost:8080/swagger-ui.html

If it refuses to start with `JWT_SECRET must be set`, the `.env` was not sourced — that
failure is deliberate (see `PHASE0_SETUP.md` §2).

---

## 3. Frontend

```bash
cd frontend
npm ci          # first run only
npm start       # regenerates the API client, then serves on :4200
```

`npm start` proxies `/api` to `localhost:8080` via `proxy.conf.json`. Use
`npm run start-remote` to develop against the hosted API instead.

---

## 4. Getting a score to practise

The practice route needs a score that has a **`ScoreDocument`**, not just an uploaded
file. Three ways to get one, cheapest first:

### 4a. Build a document directly from MusicXML (no OMR, seconds)

Fastest path, and it exercises the same builder the pipeline uses:

```bash
cd worker
python3 - <<'PY'
from pathlib import Path
from pilearn_worker.parser.musicxml_parser import parse_musicxml
from pilearn_worker.parser.document_builder import build_document, BuildOptions

raw = parse_musicxml(Path("/path/to/score.musicxml"))
doc = build_document(raw, BuildOptions(score_id="00000000-0000-0000-0000-000000000001"))
Path("document.json").write_text(doc.model_dump_json(indent=2))
print(f"{doc.meta.measure_count} measures, {len(doc.chunks)} chunks, "
      f"{len(doc.alignment.steps)} steps")
PY
```

Then POST it to the backend (see `ScoreDocumentService.save`), or load it through the
worker's `/api/v1/scores/{id}/document` endpoint.

### 4b. Full ingestion through the worker

```bash
curl -X POST localhost:8000/api/v1/omr/process \
  -F "scoreId=00000000-0000-0000-0000-000000000001" \
  -F "title=Test Piece" \
  -F "composer=Test" \
  -F "file=@/path/to/score.pdf"

# Poll — status goes QUEUED -> RUNNING -> COMPLETED | REVIEW_REQUIRED | FAILED
curl -s localhost:8000/api/v1/omr/jobs/<jobId> | jq '{status, stage, progress, pages}'
```

Watch `pages`: `droppedPages` non-empty means bars are missing from the score, and the
job lands in `REVIEW_REQUIRED` rather than `COMPLETED`. That is working as intended.

### 4c. Existing library score

Any score whose `processing_status` is `COMPLETED` or `REVIEW_REQUIRED`:

```bash
psql "$DB_URL" -c \
  "SELECT id, title, processing_status, current_revision
   FROM pianoml.score WHERE current_revision IS NOT NULL LIMIT 5;"
```

---

## 5. Open the practice surface

```
http://localhost:4200/practice/<scoreId>
```

Optionally pin a document revision:

```
http://localhost:4200/practice/<scoreId>?revision=2
```

---

## 6. Browser testing checklist

### 6.1 AudioContext unlock

Browsers refuse to start audio without a user gesture. The surface is built around this:
the soundfont is fetched during bootstrap, but `Tone.start()` only runs inside the Play
click.

- [ ] Load the route. No audio, no console errors, no autoplay warning.
- [ ] Click **Play**. Audio starts. `Tone.getContext().state` is `running`.
- [ ] Reload and press **Space** without clicking first — a keypress is also a valid
      user gesture, so this must work too.
- [ ] If audio is silent but the cursor moves: check `Tone.getContext().state` in the
      console. `suspended` means the gesture did not reach `Tone.start()`.

The first Play should not stall. If it does, the soundfont did not warm during bootstrap
— check the network tab for the `/assets/soundfonts/` request.

### 6.2 WebMIDI

WebMIDI is **Chromium-only**. Firefox and Safari get playback and cursor tracking with
scoring disabled.

- [ ] Connect a MIDI keyboard **before** loading the page.
- [ ] Chrome prompts for MIDI access on first use. Accept it.
- [ ] `navigator.requestMIDIAccess` resolves — check in the console:
      ```js
      navigator.requestMIDIAccess().then(a => console.log([...a.inputs.values()]))
      ```
- [ ] Press a key: it lights on the on-screen keyboard even when not playing (free play).
- [ ] Press **Play**, then a correct note: the key lights and accuracy stays at 100%.
- [ ] Press a wrong note: the key turns red and accuracy drops.
- [ ] No device? The on-screen keyboard is clickable and scores pitch (but not timing —
      a mouse click has no meaningful onset to measure against).

**If the permission prompt never appears:** WebMIDI requires a secure context.
`localhost` counts as secure; a LAN IP such as `192.168.x.x` does not, and the prompt
will be silently skipped.

### 6.2b Non-MIDI input

No hardware is the common case, not an edge case.

- [ ] With no device connected, the input selector shows **MIDI keyboard — No device
      detected** (greyed, not hidden) and **Click / touch** is active.
- [ ] Click a key: it sounds and lights. Click during an attempt: it is scored for pitch.
- [ ] **Drag across several keys**: each sounds in turn (glissando), and releasing
      *outside* the keyboard leaves no key stuck lit.
- [ ] Switch to **Computer keyboard**: `[A] [W] [S] [E] [D]…` labels appear on the keys.
- [ ] `A S D F G H J K` plays C D E F G A B C; `W E T Y U` plays the sharps.
- [ ] **Hold a key down**: it sounds ONCE. OS key-repeat must not register dozens of
      notes — if accuracy collapses while holding a key, the repeat guard regressed.
- [ ] `Z` / `X` shift the octave; the key labels follow, and held notes release rather
      than stranding.
- [ ] Focus the tempo slider and press `A`: no note sounds.
- [ ] Plug in a MIDI keyboard mid-session: the selector switches to MIDI automatically.

### 6.3 Transport shortcuts

Shortcuts are ignored while a text field has focus, and while the summary overlay is
open.

| Key | Action |
|---|---|
| `Space` | Play / Stop |
| `R` | Restart chunk |
| `L` | Toggle loop |
| `G` | Toggle guide track (hands-separate stages only) |
| `Z` / `X` | Octave down / up (computer-keyboard mode) |

In computer-keyboard mode the piano mapping takes precedence, but `R`, `L` and `G` are
deliberately excluded from it so the transport shortcuts always work.

- [ ] `Space` starts and stops, and does **not** scroll the page.
- [ ] Focus the tempo slider, press `Space` — the transport must **not** toggle.
- [ ] `R` restarts from the chunk's first bar with the count-in.
- [ ] `L` toggles the Loop button state.

### 6.4 Guide track (hands-separate)

- [ ] Select a **Right hand** stage. The Guide button is enabled.
- [ ] Press Play: you hear the **left** hand while the cursor tracks the right.
- [ ] Switch to a **Left hand** stage: you now hear the **right** hand.
- [ ] Switch to a **Both hands** stage: the Guide button greys out and nothing is
      synthesised — otherwise your own playing would be masked.
- [ ] Drag the guide volume down: the accompaniment quietens; your own notes do not.
- [ ] **Loop boundary test (the important one):** enable Loop, start a chunk ending on a
      held chord, and let it loop 4–5 times. No note should ring across the restart, and
      the sound must not thicken with each pass. Accumulating voices means the note-off
      flush regressed.

### 6.5 Cursor and chunk highlighting

- [ ] Bars outside the active chunk are dimmed.
- [ ] The cursor advances in time with the audio, not ahead of or behind it.
- [ ] The score auto-scrolls when the cursor nears the edge, and does **not** twitch on
      every step.
- [ ] Changing stage to a different chunk re-dims and scrolls to the new range.

### 6.6 Attempt summary

- [ ] With Loop **off**, play a chunk to the end: the summary opens automatically.
- [ ] With Loop **on**: it must **not** open — it restarts silently instead.
- [ ] The summary's weak-bar list matches where you actually played wrong notes.
- [ ] "Next stage" appears only once the stage's clean-run requirement is met.

### 6.7 Degraded paths

- [ ] Open a score whose `processing_status` is `REVIEW_REQUIRED`: the amber "needs
      review" banner appears above the score.
- [ ] Open a score with no roadmap: the surface still loads with an explanatory notice
      rather than an error page.
- [ ] Open a nonexistent score id: a clear "not found" message, not a blank screen.

---

## 7. Verification commands

```bash
# Frontend
cd frontend
npx tsc --noEmit -p tsconfig.app.json                    # expect: exit 0
npx ng build --configuration=production --no-prerender   # expect: success

# Worker
cd ../worker && python3 -m pytest tests -q                # expect: 127 passed

# Backend
cd ../backend && ./mvnw -B clean verify
```

### Known build warnings

Three warnings are pre-existing and unrelated to the practice surface:

| Warning | Cause |
|---|---|
| `bundle initial` over by ~43 kB | Legacy routes; the practice route is lazy-loaded and not in the initial bundle |
| `layout.component.css` 16.11 kB | Heavy `@apply` usage in a legacy component |
| `blog-post.component.css` 4.06 kB | 64 bytes over |

If you see a component stylesheet reported at ~35 kB, it has an
`@import "tailwindcss"` that should be `@reference "tailwindcss"` — that import inlines
the whole framework into the component's scoped styles.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Backend exits: `JWT_SECRET must be set` | `.env` not sourced | `set -a && source ../.env && set +a` |
| Practice route renders blank | Score has no `ScoreDocument` | Check `current_revision` is not null (§4c) |
| Score area empty, no error | MusicXML fetch failed | Network tab: `/api/v1/scores/{id}/musicxml` |
| Cursor does not move | No alignment index | `curl .../document \| jq '.alignment.steps \| length'` |
| Audio silent, cursor moving | AudioContext suspended | §6.1 |
| MIDI keys do nothing | Device not enabled, or non-Chromium | §6.2 |
| Notes ring across a loop | Guide note-off flush regressed | §6.4 last item; see `stopAllGuideNotes` |
| `npm install` fails on a git dep | A `github:` dependency crept back in | `grep -n 'github:' frontend/package.json` |
| Liquibase checksum error | An applied changeset was edited | Never edit applied changesets. Locally: `docker compose down -v` |
