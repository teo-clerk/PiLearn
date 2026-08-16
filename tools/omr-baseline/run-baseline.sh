#!/usr/bin/env bash
#
# OMR baseline harness — host entrypoint.
#
# Brings up the OMR toolchain container, runs the legacy pipeline over the fixture
# corpus, and (optionally) diffs the result against the committed golden snapshots.
#
#   ./tools/omr-baseline/run-baseline.sh                    run all fixtures
#   ./tools/omr-baseline/run-baseline.sh --check            run, then diff vs golden
#   ./tools/omr-baseline/run-baseline.sh --promote          run, then write golden
#   ./tools/omr-baseline/run-baseline.sh --id chopin-prelude-e-minor
#
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
MODE="none"
FIXTURE_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)   MODE="check"; shift ;;
    --promote) MODE="promote"; shift ;;
    --id)      FIXTURE_ARGS+=(--id "$2"); shift 2 ;;
    --run-id)  RUN_ID="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ${#FIXTURE_ARGS[@]} -eq 0 ]]; then
  FIXTURE_ARGS=(--all)
fi

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# ── Preflight ────────────────────────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || { echo "docker not found on PATH" >&2; exit 1; }

if [[ ! -f .env ]]; then
  echo "ERROR: .env not found. Run: cp .env.example .env  (then fill it in)" >&2
  exit 1
fi

if [[ ! -d Scores ]]; then
  echo "ERROR: Scores/ not found — the fixture corpus must be present locally." >&2
  echo "       It is gitignored by design; fetch it before running the baseline." >&2
  exit 1
fi

# ── Bring up the toolchain ───────────────────────────────────────────────────
log "Starting the OMR toolchain container (first build takes 20-40 min)"
docker compose --profile omr up -d --build omr

log "Waiting for the container to be ready"
for _ in $(seq 1 30); do
  if docker compose exec -T omr test -f /home/appuser/scripts/pdf2pack.sh 2>/dev/null; then
    break
  fi
  sleep 2
done

if ! docker compose exec -T omr test -f /home/appuser/scripts/pdf2pack.sh; then
  echo "ERROR: pdf2pack.sh not present in the container — image build is incomplete." >&2
  exit 1
fi

# ── Run ──────────────────────────────────────────────────────────────────────
log "Running the legacy pipeline (run id: $RUN_ID)"
set +e
docker compose exec -T omr \
  /home/appuser/shared-venv/bin/python \
  /home/appuser/omr-baseline/harness/baseline.py \
  --run-id "$RUN_ID" "${FIXTURE_ARGS[@]}"
RUN_STATUS=$?
set -e

REPORT_DIR="tools/omr-baseline/reports/$RUN_ID"
if [[ ! -d "$REPORT_DIR" ]]; then
  echo "ERROR: no reports produced at $REPORT_DIR" >&2
  exit 1
fi

log "Reports written to $REPORT_DIR"

# ── Compare / promote ────────────────────────────────────────────────────────
case "$MODE" in
  check)
    log "Diffing against golden snapshots"
    python3 tools/omr-baseline/harness/compare.py check --run "$RUN_ID"
    ;;
  promote)
    log "Promoting this run to golden"
    python3 tools/omr-baseline/harness/compare.py promote --run "$RUN_ID"
    echo
    echo "Review 'git diff tools/omr-baseline/golden/' before committing."
    ;;
  none)
    echo
    echo "Next:"
    echo "  review       cat $REPORT_DIR/summary.json"
    echo "  lock in      python3 tools/omr-baseline/harness/compare.py promote --run $RUN_ID"
    echo "  regression   python3 tools/omr-baseline/harness/compare.py check   --run $RUN_ID"
    ;;
esac

# A non-zero pipeline run is informative, not fatal: a fixture the legacy pipeline
# cannot process is itself a baseline finding worth recording.
exit "$RUN_STATUS"
