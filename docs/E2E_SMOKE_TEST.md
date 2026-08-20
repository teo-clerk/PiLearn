# End-to-End Browser Smoke Test

The one check nothing else substitutes for.

Three test suites pass (worker `pytest`, backend `mvn verify`, frontend `tsc` + `ng build`)
and none of them opens a browser. Everything below is a claim those suites cannot make:
that a person can land on the site, hand it a PDF, and end up playing.

Run this after any change to ingestion, the practice surface, or the dev stack.

---

## 0. Start the stack

```bash
cp .env.example .env      # first time only; fill in the REQUIRED values
tools/dev-up.sh
```

Expect: every line ticked, ending in the URL block. If a port is taken the script
names it and stops — see the three ways out it prints.

The first run builds the OMR worker image (~6 GB, 20-40 minutes). Subsequent runs
start it in about two minutes, which is the torch model loading.

The engine repositories in `worker/Dockerfile` are pinned to commit SHAs. Bump them
deliberately and re-run the OMR baseline harness afterwards — a moved pin silently
changes recognition, and the golden baseline with it.

**Have ready:** a 2-page piano PDF. `Scores/` in this repo has fixtures.

---

## 1. Landing page and the skill questionnaire

Open <http://localhost:4200>.

The dev server proxies `/api` to `http://localhost:8080`. It used to point at the hosted
API, so a local frontend silently talked to production and every locally-added endpoint
looked missing — if you see 404s for `/profile` or `/library`, check `proxy.conf.json`
first.

- [ ] The page renders — hero, how-it-works, demo shelf. Not a blank screen, not a
      bare "Import" breadcrumb.
- [ ] No red errors in the browser console. Warnings about CommonJS bundling are
      expected and harmless.
- [ ] The first-run tour appears. Dismiss it.
- [ ] **The skill questionnaire appears** — two questions, experience and reading.
      Answer *"Never touched a piano"* and *"No, I need visual aids"*.
- [ ] Reload the page. It does **not** ask again — the answer was saved against your
      guest session, and re-asking every visit is the failure this gates against.
- [ ] **You are signed out.** Check: no account name in the navbar. If you have a
      session from a previous run, sign out — this test is specifically about the
      anonymous path.

## 2. Guest upload

- [ ] Drag the PDF onto the hero drop target. It highlights on drag-over.
- [ ] Upload starts **without a sign-in prompt.** A 401 here is the exact
      regression this flow was built to remove.
- [ ] The stepper advances through Upload → Recognise → Analyse → Plan. Byte
      progress moves during Upload; the later stages come from polling.
- [ ] The view does **not** say "simulated". That label means the browser could not
      reach the backend and is showing a canned run — real ingestion is not being
      tested. If you see it, check `tail -f .dev/logs/backend.log`.

Confirm server-side while it runs:

```bash
# The score exists, is owned by the guest account, and carries a session id.
docker exec -i pilearn-postgres psql -U pilearn -d pianoml -c \
  "SELECT id, title, processing_status, guest_session_id FROM pianoml.score
   ORDER BY updated_at DESC LIMIT 3;"
```

- [ ] `guest_session_id` is populated (`guest_…`) and `processing_status` moves
      `QUEUED` → `RUNNING` → `COMPLETED`.
- [ ] In the browser devtools, Application → Local Storage → `pilearn.guestSession`
      holds the same id. Upload a second file: the id must **not** change.

## 3. Recognition completes

- [ ] Status reaches `READY` (or `REVIEW_REQUIRED` — also a pass; a partially
      recognised score is still practisable).
- [ ] The app routes itself to `/practice/<scoreId>`. No manual navigation, no
      dead end on the import screen.

If it stalls, the worker is the place to look:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml logs -f worker
```

## 3b. The plan adapts to the answer

- [ ] The roadmap has **one-bar chunks**, not four-bar phrases. A complete beginner is
      given a bar at a time.
- [ ] The first unit is **"Feel the pulse"** with a single rhythm stage.
- [ ] The last unit is **"Put it together"** — the whole piece, which they have never
      played end to end.

Cross-check without the browser:

```bash
curl -s "http://localhost:8080/api/v1/scores/<scoreId>/roadmap?skillLevel=BEGINNER_0" \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["totalStages"], [c["label"] for c in r["chunks"]][:3])'
curl -s "http://localhost:8080/api/v1/scores/<scoreId>/roadmap?skillLevel=ADVANCED" \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["totalStages"], [c["label"] for c in r["chunks"]][:3])'
```

The two must differ in chunk size and stage count. If they are identical, the profile is
not reaching the roadmap generator.

## 4. Practice surface

- [ ] Sheet music renders (OSMD). Bars, clefs, notes — not an empty box.
- [ ] The guest banner appears: *"You're practising as a guest."* with a
      **Create free account** link. It is dismissible and does not block the score.
- [ ] The virtual keyboard renders and its highlighted keys match the notes under
      the cursor.
- [ ] The roadmap / stage guide shows a first chunk with a bar range.
- [ ] If the score needed review, the review banner is at the top of the surface,
      not buried.

## 4a. Audio permission

- [ ] Before pressing Play, click a piano key. Either you hear it, **or** a
      **"Click to enable audio"** banner is showing. A silent keyboard with no banner is
      the failure — it reads as broken software rather than as a permission you can grant.
- [ ] Click the banner. It disappears and keys sound from then on.
- [ ] Pressing Play also enables audio, so someone who starts there never sees the banner.

## 4b. Wait-for-Me and rhythm stages (beginner ladders only)

- [ ] A bar showing the stage name is above the score, e.g. *"Tap the rhythm — any key"*.
- [ ] **Rhythm stage:** press any key on the beat. It counts as a hit — pressing the
      "wrong" pitch must not be scored WRONG, because the screen said any key.
- [ ] The piece is **not** played back during a rhythm stage. Hearing the notes would
      tell the learner nothing about whether they tapped in time.
- [ ] `Shift`+`L` toggles loop and `Shift`+`G` toggles the guide track. Plain `L` and `G`
      play notes — both are piano keys, which is why the toggles take Shift.
- [ ] **Wait-for-Me stage:** the badge reads *"Waiting for you"*. Do nothing for thirty
      seconds — the cursor must **not** move. There is no timer and no deadline.
- [ ] Play the wrong note. Nothing advances, and nothing is taken away.
- [ ] Play the right note. The cursor advances exactly one step.
- [ ] On a chord, the cursor waits for **every** note, in any order, with gaps between
      them — pressing them one at a time is what a beginner does and is correct.
- [ ] Note names (`C4`, `G3`) are drawn on the expected keys while labels are on, and
      only on the expected keys.
- [ ] On the final stage of the ladder the labels come **off**. That is the point of it.

## 5. Playing — QWERTY

- [ ] Choose **Computer keyboard** in the input selector.
- [ ] Press the expected key. You **hear** the note. Silent scoring is a regression:
      every input path must sound the learner's own note.
- [ ] The note is scored — the HUD shows a verdict and a timing deviation.
- [ ] The cursor advances to the next step.
- [ ] The cursor stays in step with the audio through a full chunk. Drift here means
      alignment is reading the wrong clock.

## 6. Playing — MIDI

Skip if no device is attached; say so in the result rather than marking it passed.

- [ ] Connect a MIDI keyboard, choose **MIDI** in the selector. The device is named.
- [ ] Playing a key sounds and scores it, same as QWERTY.
- [ ] With hands-separate mode on, the opposing hand plays back as accompaniment on
      its own channel while yours stays silent until you play it.

## 7. Metronome and tempo

- [ ] The count-in matches the score's time signature. A 6/8 score must not get a
      4/4 pulse — that specific bug has shipped before.
- [ ] Changing the tempo slider changes playback speed, and the cursor follows.

## 7b. My scores

Open <http://localhost:4200/library/my-scores>.

- [ ] The score you just practised is listed, with a progress bar and a line like
      *"Stage 4/28 · chunk 2 · 75% BPM"*.
- [ ] **Resume practice** reopens the score.
- [ ] A score still being processed shows why instead of a button that would open a
      score with no notes in it.
- [ ] The guest banner offers an account to keep the library.

The isolation check that matters — one guest must never see another's scores:

```bash
# Your own library has the score.
curl -s "http://localhost:8080/api/v1/scores/library?guestSessionId=<yourSession>"
# A different session must come back empty, NOT with your scores.
curl -s "http://localhost:8080/api/v1/scores/library?guestSessionId=guest_someoneelse12345678"
```

Every guest shares one seeded account, so a library scoped by owner alone would show
each visitor everyone else's uploads. The second command returning `[]` is what proves
it does not.

## 8. Reload and return

- [ ] Reload `/practice/<scoreId>`. The score loads again from the backend.
- [ ] Still no sign-in prompt, and the guest banner is back.

## 9. Shut down

```bash
tools/dev-down.sh              # keeps your data
tools/dev-down.sh --volumes    # destroys the database and all uploads
```

---

## Recording the result

Note, for each numbered section: pass, fail, or skipped-and-why. A section left
unmentioned reads as passed, which is the failure mode this document exists to
prevent — an untested step is not a passing step.

Regressions worth naming explicitly if they reappear:

| Symptom | Where to look |
|---|---|
| 401 on upload | `SecurityConfig` POST permit list; `UploadOwnerResolver` |
| "simulated" label | backend unreachable — `.dev/logs/backend.log` |
| Blank `/import` | a route missing from `importRouteList` |
| Blank score panel | `getMusicXml()` — the derived artefact never fetched |
| Silent playing | learner notes not routed to channel 0 |
| Cursor drift | deviation read from the beat grid instead of the transport clock |
| 404 on /profile or /library | dev proxy pointing at the hosted API, not localhost:8080 |
| Questionnaire every visit | `onboarded` cleared by a later partial profile update |
| Same roadmap at every level | profile not reaching the roadmap generator (session id missing) |
| Cursor moves during Wait-for-Me | the transport was scheduled for a WAIT stage |
| READY but /document 404s | nothing pulled the worker's output across — see `ScoreStatusController` |
| Re-uploading a PDF gives an unplayable score | dedup ingested into the first submitter's score |
| Empty stave on a re-upload | the engraving source was not copied to the new score |
| "pipeline script exited 1" and nothing else | check the worker log for the captured stderr |
| Silent keyboard | AudioContext suspended — the "Click to enable audio" banner should be showing |
| One guest sees another's library | a query scoped by owner without the guest session |
