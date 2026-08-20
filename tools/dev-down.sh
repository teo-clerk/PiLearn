#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# PiLearn — stop everything tools/dev-up.sh started.
#
#   tools/dev-down.sh            stop containers and host processes
#   tools/dev-down.sh --volumes  ...and delete the database and object storage
#
# --volumes is destructive and irreversible: every uploaded score, every
# processed document and the whole database go with it.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PID_DIR="$REPO_ROOT/.dev/pids"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.dev.yml)

WIPE=0
if [[ "${1:-}" == "--volumes" ]]; then WIPE=1; fi

ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }

for pidfile in "$PID_DIR"/*.pid; do
  [[ -e "$pidfile" ]] || continue
  name="$(basename "$pidfile" .pid)"
  pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    # Signal the process group, because mvnw and `ng serve` both fork children
    # that survive a kill aimed at the wrapper alone. dev-up.sh puts each child
    # in its own group via setsid, so this can never reach the user's shell —
    # but verify that before signalling rather than trusting it.
    pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')"
    if [[ -n "$pgid" && "$pgid" != "$(ps -o pgid= -p $$ | tr -d ' ')" ]]; then
      kill -TERM -- "-$pgid" 2>/dev/null || true
    else
      kill -TERM "$pid" 2>/dev/null || true
    fi
    # Wait for it to actually die. Returning while the JVM is still shutting down
    # means the next dev-up.sh sees 8080 bound and refuses to start — "stopped"
    # has to mean stopped, not "asked nicely".
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
      if [[ -n "$pgid" && "$pgid" != "$(ps -o pgid= -p $$ | tr -d ' ')" ]]; then
        kill -KILL -- "-$pgid" 2>/dev/null || true
      else
        kill -KILL "$pid" 2>/dev/null || true
      fi
      ok "stopped $name (pid $pid, forced)"
    else
      ok "stopped $name (pid $pid)"
    fi
  fi
  rm -f "$pidfile"
done

if [[ $WIPE -eq 1 ]]; then
  printf '\n\033[33mThis deletes the database and all uploaded scores.\033[0m\n'
  read -r -p 'Type "delete" to confirm: ' answer
  [[ "$answer" == "delete" ]] || { echo "Cancelled."; exit 1; }
  "${COMPOSE[@]}" --profile omr --profile backend down --volumes
  ok "containers and volumes removed"
else
  "${COMPOSE[@]}" --profile omr --profile backend down
  ok "containers stopped (data volumes kept)"
fi
