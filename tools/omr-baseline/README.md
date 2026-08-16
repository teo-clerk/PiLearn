# OMR Baseline Harness

Measures the **current (legacy) OMR pipeline** against the fixture corpus, so that
Phase 2's migration to a typed FastAPI worker can be judged against a number instead
of a feeling.

> This harness deliberately does **not** fix the legacy pipeline. It measures it as-is,
> defects included. Fixing it is Phase 2 work; the point of this harness is to know
> whether those fixes help.

---

## Why this exists

`backend/scripts/pdf2pack.sh` is 117 lines of shell invoked via `ProcessBuilder`, with no
tests, no confidence signal, and a failure mode that matters: **it only fails when *every*
page fails**. A 12-page score whose page 7 fails OMR silently becomes an 11-page score, and
nothing downstream knows. Any learning roadmap built on it teaches the wrong bars.

Two distinct questions, two distinct answers:

| Question | Mechanism | Needs a human? |
|---|---|---|
| *Did my change break recognition?* | golden snapshot diff | no — runs in CI |
| *Is the recognition any good?* | accuracy vs hand-entered ground truth | yes, once per fixture |

The harness serves both. Regression checking works today; accuracy scoring activates as
`groundTruth` blocks get filled in (task **P1-T13**).

---

## Layout

```
tools/omr-baseline/
  run-baseline.sh          host entrypoint — brings up the container and runs everything
  fixtures/manifest.json   corpus registry: sha256, page counts, ground truth  [committed]
  harness/
    metrics.py             deterministic metric extraction from a packed archive
    baseline.py            runs the legacy pipeline per fixture   (inside container)
    compare.py             promote / check against golden          (host, stdlib only)
  golden/                  committed snapshots — the regression contract  [committed]
  reports/                 per-run output                          [gitignored]
```

The fixture **PDFs are not committed** — they are private local test material. The manifest
pins each by sha256 so a drifting corpus is detected rather than silently changing the baseline.

---

## Running it

### Prerequisites

- Docker with the Compose plugin
- `Scores/` present locally (the corpus)
- `.env` filled in (`cp .env.example .env`)
- Python 3.9+ on the host (for `compare.py` — stdlib only, no install needed)

### First run

```bash
# Build the toolchain image and run every fixture.
# First build pulls CPU torch + MuseScore3 + homr: 20-40 min, ~6 GB.
./tools/omr-baseline/run-baseline.sh

# Read the summary.
cat tools/omr-baseline/reports/<run-id>/summary.json
```

### Lock in the baseline

Once you have reviewed a run and believe it represents the current pipeline honestly:

```bash
python3 tools/omr-baseline/harness/compare.py promote --run <run-id>
git add tools/omr-baseline/golden/
git commit -m "test: lock in OMR baseline from legacy pipeline"
```

### Check for regressions

```bash
./tools/omr-baseline/run-baseline.sh --check      # exits 1 on regression
```

### Single fixture, fast iteration

```bash
./tools/omr-baseline/run-baseline.sh --id chopin-prelude-e-minor --check
```

### Manual invocation inside the container

```bash
docker compose --profile omr up -d
docker compose exec omr ~/shared-venv/bin/python \
    omr-baseline/harness/baseline.py --id zimmer-interstellar
```

---

## What is measured

### Compared (deterministic — a change here is a real regression)

**From MusicXML** — via `music21`:
`part_count`, `staff_count`, `measure_count`, `note_count`, `rest_count`, `chord_count`,
`tied_note_count`, `distinct_pitch_count`, pitch range, key signatures, time signatures,
`has_fingering`, `has_harmony`, `repeat_count`, `volta_count`, **`measure_note_counts`**,
`content_hash`.

**From MIDI** — via `pretty_midi`:
`track_count`, `note_count`, `ppq`, `distinct_pitch_count`, `pitch_class_histogram`,
`content_hash`.

`measure_note_counts` is the highest-value signal: when it changes, `compare.py` reports the
**first divergent measure**, so a regression localises to a bar range instead of a
whole-document delta.

`content_hash` is an order-independent SHA-256 over every `(measure, offset, midi)` triple —
it catches pitch and rhythm changes that leave the aggregate counts identical.

### Recorded but never compared (non-deterministic)

Wall time, exit code, `homr` per-page success/failure counts, stdout/stderr logs. These vary
by machine and load; comparing them would produce flaky failures and teach everyone to ignore
the check.

The homr summary is parsed into `execution.omr` including a **`silentPartialFailure`** flag —
true whenever the pipeline exits 0 having dropped pages. That flag is the concrete evidence
for the P2 fix in `DATA_PIPELINE.md`.

### Tolerances

`midi.duration_sec` tolerates ±0.05 s. Everything else is exact.

---

## Filling in ground truth (P1-T13)

For each fixture, open the PDF, read the printed score, and fill its `groundTruth` block in
`fixtures/manifest.json`:

```json
"groundTruth": {
  "measureCount": 25,
  "staffCount": 2,
  "keySignature": "1",
  "timeSignature": "4/4",
  "hasLyrics": false,
  "hasRepeats": false,
  "verifiedBy": "your-name",
  "verifiedAt": "2026-08-20"
}
```

`keySignature` is the **sharp count as a signed integer string** (music21's convention):
`-2` = B♭ major / G minor, `0` = C major / A minor, `1` = G major / E minor.

Count measures as printed, including any pickup bar, excluding repeat expansion.

The next run scores recognition against these and writes `accuracy.overall` per fixture.
Roll the results into `docs/OMR_BASELINE.md` — that table is what Phase 2 must beat.

---

## Known corpus gaps

Recorded in `manifest.json` under `coverageGaps`, repeated here because they bound what the
baseline can tell you:

1. **7 of 10 fixtures are single-page.** The `relieur` merge stage and the silent
   partial-failure path are barely exercised. Add at least one 8+ page score before trusting
   any merge-related number.
2. **All fixtures are born-digital engraving.** No scans or photographs — which is the harder
   real-world input and where homr and Audiveris diverge most. Add one scanned score before
   Phase 2 concludes, or the two-engine arbitration (P2-T08) will be tuned on the easy case.

---

## Harness accommodations to the legacy pipeline

Two deliberate deviations, both documented so nobody mistakes them for pipeline behaviour:

1. **Filename sanitisation.** Fixtures are staged as `upload_<fixture-id>.pdf` before
   invocation. The corpus filenames contain spaces, accents, ampersands and curly
   apostrophes, which the unquoted globs in `pdf2pack.sh` cannot survive (`AUDIT §S3`). That
   is a separately tracked shell-quoting bug; measuring OMR quality is not the place to
   discover it. The staged name also mirrors what `PackService` actually does in production.
2. **No `set -e` compensation.** `pdf2pack.sh` has a stray `n start` line (`AUDIT §S2`) that
   fails harmlessly on any machine without the `n` node manager. Its stderr is captured in the
   run log rather than suppressed.

---

## CI integration

The full corpus takes tens of minutes — too slow for every push. Wire it as a **nightly** job:

```yaml
# .github/workflows/omr-baseline.yml
name: OMR baseline
on:
  schedule: [{ cron: "0 3 * * *" }]
  workflow_dispatch:
jobs:
  baseline:
    runs-on: ubuntu-latest
    timeout-minutes: 120
    steps:
      - uses: actions/checkout@v4
      - name: Fetch fixture corpus
        run: ./tools/local/fetch-fixtures.sh      # pulls Scores/ from private storage
        env:
          FIXTURES_URL: ${{ secrets.FIXTURES_URL }}
      - name: Run baseline and check against golden
        run: ./tools/omr-baseline/run-baseline.sh --check
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: omr-baseline-reports
          path: tools/omr-baseline/reports/
```

Pull requests that touch `backend/scripts/**` or `backend/Dockerfile` should trigger it too,
via a `paths:` filter on `pull_request`.
