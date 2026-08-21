#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# PiLearn — bring up the whole stack with one command.
#
#   tools/dev-up.sh                    infra + backend + frontend
#   tools/dev-up.sh --infra-only       just Postgres / MinIO / Redis / worker
#   tools/dev-up.sh --no-worker        skip the 6 GB OMR image (uploads will fail)
#   tools/dev-up.sh --backend-in-docker  run Spring in a container, not on the host
#   tools/dev-up.sh --help
#
# Stop everything again with tools/dev-down.sh.
#
# The script refuses to start rather than starting halfway. A stack that is up
# on three of five ports is harder to diagnose than one that never started, and
# the failure surfaces minutes later as an unexplained connection refused.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LOG_DIR="$REPO_ROOT/.dev/logs"
PID_DIR="$REPO_ROOT/.dev/pids"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.dev.yml)

INFRA_ONLY=0
WITH_WORKER=1
BACKEND_IN_DOCKER=0

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
info()  { printf '  \033[36m•\033[0m %s\n' "$*"; }
ok()    { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn()  { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()   { printf '\n\033[31m✗ %s\033[0m\n\n' "$*" >&2; exit 1; }

usage() { sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --infra-only)         INFRA_ONLY=1 ;;
    --no-worker)          WITH_WORKER=0 ;;
    --backend-in-docker)  BACKEND_IN_DOCKER=1 ;;
    -h|--help)            usage ;;
    *)                    die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

# ── 1. Prerequisites ─────────────────────────────────────────────────────────
bold "Checking prerequisites"

command -v docker >/dev/null 2>&1 || die "docker is not installed or not on PATH."
docker compose version >/dev/null 2>&1 \
  || die "the docker compose plugin is missing. Install docker-compose-plugin."
docker info >/dev/null 2>&1 \
  || die "the Docker daemon is not reachable. Start Docker and try again."
ok "docker is running"

if [[ ! -f .env ]]; then
  die ".env is missing. Create it with:

    cp .env.example .env
    # then fill in the REQUIRED values:
    #   JWT_SECRET        openssl rand -base64 64 | tr -d '\\n'
    #   DB_PASSWORD       any local value
    #   STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY   any local values"
fi

# Read .env without exporting it: these are only needed for the checks below,
# and compose reads the file itself.
missing=()
for key in JWT_SECRET DB_PASSWORD STORAGE_ACCESS_KEY STORAGE_SECRET_KEY; do
  value="$(grep -E "^${key}=" .env | head -1 | cut -d= -f2- | tr -d '"'"'"'[:space:]' || true)"
  if [[ -z "$value" ]]; then missing+=("$key"); fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
  die ".env is missing required values: ${missing[*]}

  JWT_SECRET must be at least 64 characters. Generate one:
    openssl rand -base64 64 | tr -d '\\n'"
fi
ok ".env has the required values"

# Java is only needed when the backend runs on the host.
if [[ $INFRA_ONLY -eq 0 && $BACKEND_IN_DOCKER -eq 0 ]]; then
  command -v java >/dev/null 2>&1 || die "java is not on PATH. Install a JDK 21, or pass --backend-in-docker."
  java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  [[ "$java_major" -ge 21 ]] 2>/dev/null \
    || die "Java 21+ is required (found ${java_major:-unknown}). Install a JDK 21, or pass --backend-in-docker."
  ok "java $java_major"
fi

# Node is needed whenever the web app runs, which is every mode except --infra-only.
# This check used to sit under the Java branch above, so --backend-in-docker skipped it
# and then started `npm start` anyway — failing silently inside a log file nobody was
# watching, on the very path the README recommends.
if [[ $INFRA_ONLY -eq 0 ]]; then
  command -v node >/dev/null 2>&1 || die "node is not on PATH. Install Node 20+."
  ok "node $(node --version)"
fi

# ── 2. Ports ─────────────────────────────────────────────────────────────────
# A port already held by one of our own containers is fine — that is a restart,
# not a conflict. Anything else is someone else's process and must be named.
bold "Checking ports"

# Name the process holding a port. `ss` only reveals the owner to root, so an
# empty answer is normal rather than a failure — say so instead of printing the
# peer-address column and calling it a process.
port_holder() {
  local port="$1" holder=""
  if command -v ss >/dev/null 2>&1; then
    # ss renders the owner as: users:(("java",pid=450294,fd=225))
    holder="$(ss -ltnp 2>/dev/null \
      | awk -v p=":$port\$" '$4 ~ p' \
      | sed -nE 's/.*users:\(\("([^"]+)",pid=([0-9]+).*/\1 (pid \2)/p' \
      | head -1)"
  fi
  if [[ -z "$holder" ]] && command -v lsof >/dev/null 2>&1; then
    holder="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1" (pid "$2")"}')"
  fi
  [[ -n "$holder" ]] && printf '%s' "$holder" || printf 'unknown (re-run with sudo to see the owner)'
}

# The pid listening on a port, if `ss` will tell us.
port_pid() {
  ss -ltnp 2>/dev/null \
    | awk -v p=":$1\$" '$4 ~ p' \
    | sed -nE 's/.*pid=([0-9]+).*/\1/p' \
    | head -1
}

# Is this port published by one of our own containers? That is a restart, not a
# conflict. Docker collapses contiguous published ports into a range
# ("0.0.0.0:9000-9001->9000-9001/tcp"), so a plain match on "<port>->" misses
# every port but the last one in a range.
# A port held by a process this script started earlier is a restart, not a conflict:
# start_host_process below will notice it is already running and leave it alone.
port_is_our_host_process() {
  local pidfile pid
  for pidfile in "$PID_DIR"/*.pid; do
    [[ -e "$pidfile" ]] || continue
    pid="$(cat "$pidfile" 2>/dev/null)"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null || continue
    # Match the process group, since the listener is usually a child (the JVM under
    # mvnw, the node process under the ng wrapper).
    if [[ "$(ps -o pgid= -p "$1" 2>/dev/null | tr -d ' ')" \
          == "$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')" ]]; then
      return 0
    fi
  done
  return 1
}

port_is_ours() {
  docker ps --filter "name=pilearn-" --format '{{.Ports}}' 2>/dev/null \
    | tr ',' '\n' \
    | grep -- '->' \
    | sed -E 's|.*:([0-9]+(-[0-9]+)?)->.*|\1|' \
    | awk -v p="$1" -F- '
        NF == 1 && $1 == p                  { found = 1 }
        NF == 2 && p >= $1 && p <= $2       { found = 1 }
        END { exit found ? 0 : 1 }'
}

env_port() {
  local value
  value="$(grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- | tr -dc '0-9')"
  printf '%s' "${value:-$2}"
}

DB_PORT_HOST="$(env_port DB_PORT 5432)"
MINIO_PORT_HOST="$(env_port MINIO_PORT 9000)"
MINIO_CONSOLE_PORT_HOST="$(env_port MINIO_CONSOLE_PORT 9001)"
REDIS_PORT_HOST="$(env_port REDIS_PORT 6379)"
WORKER_PORT_HOST="$(env_port WORKER_PORT 8000)"
BACKEND_PORT_HOST="$(env_port BACKEND_PORT 8080)"
FRONTEND_PORT_HOST=4200

declare -a WANTED_PORTS=("$DB_PORT_HOST" "$MINIO_PORT_HOST" "$MINIO_CONSOLE_PORT_HOST" "$REDIS_PORT_HOST")
if [[ $WITH_WORKER -eq 1 ]]; then WANTED_PORTS+=("$WORKER_PORT_HOST"); fi
if [[ $INFRA_ONLY -eq 0 ]]; then
  WANTED_PORTS+=("$BACKEND_PORT_HOST")
  if [[ $BACKEND_IN_DOCKER -eq 0 ]]; then WANTED_PORTS+=("$FRONTEND_PORT_HOST"); fi
fi

# Probe both stacks. `ng serve` binds [::1] only, so an IPv4-only check reports 4200
# as free while a dev server is very much running on it — and the new one then dies
# with EADDRINUSE inside a log file nobody is watching.
port_in_use() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0
  (exec 3<>"/dev/tcp/::1/$1") 2>/dev/null && return 0
  return 1
}

conflicts=()
for port in "${WANTED_PORTS[@]}"; do
  if port_in_use "$port"; then
    if port_is_ours "$port"; then
      info "port $port — already served by a pilearn container (will be reused)"
      continue
    fi

    listener_pid="$(port_pid "$port")"
    if [[ -n "$listener_pid" ]] && port_is_our_host_process "$listener_pid"; then
      info "port $port — already served by a pilearn process (will be reused)"
      continue
    fi

    holder="$(port_holder "$port")"
    conflicts+=("$port${holder:+  held by $holder}")
  fi
done

if [[ ${#conflicts[@]} -gt 0 ]]; then
  printf '\n\033[31m✗ These ports are already in use by something else:\033[0m\n\n' >&2
  printf '    %s\n' "${conflicts[@]}" >&2
  cat >&2 <<'EOF'

  Three ways out:
    1. Stop the conflicting service.
    2. If it is an older PiLearn stack:   tools/dev-down.sh
    3. Publish on a different host port — set DB_PORT / MINIO_PORT /
       MINIO_CONSOLE_PORT / REDIS_PORT / WORKER_PORT / BACKEND_PORT in .env
       (and keep DB_URL, STORAGE_ENDPOINT and BASE_PATH pointing at the port
       you chose).

EOF
  exit 1
fi
ok "all required ports are available"

# ── 3. Infrastructure ────────────────────────────────────────────────────────
bold "Starting infrastructure"

if [[ $WITH_WORKER -eq 1 ]] && ! docker image inspect pilearn/omr-worker:local >/dev/null 2>&1; then
  warn "the OMR worker image is not built yet — first build takes 20-40 minutes."
  warn "grab a coffee, or re-run with --no-worker to skip it (uploads will fail)."
fi

compose_fail() {
  die "$1

  What went wrong is in the logs:
    ${COMPOSE[*]} logs --tail=50"
}

# --wait blocks on the healthchecks tuned in docker-compose.dev.yml, so when
# this returns the services are genuinely answering, not merely started.
#
# The long-running services are named explicitly. minio-init is a one-shot, and
# `--wait` counts any container that exits — including a successful exit — as a
# failed start, so it must not be in this set.
"${COMPOSE[@]}" up -d --wait postgres minio redis \
  || compose_fail "Postgres, MinIO or Redis did not become healthy."
ok "postgres, minio, redis are healthy"

# Idempotent: creates the media bucket if it is not already there, then exits.
"${COMPOSE[@]}" up minio-init >/dev/null 2>&1 \
  || compose_fail "the object-storage bucket could not be created."
ok "object storage bucket ready"

if [[ $WITH_WORKER -eq 1 ]]; then
  info "waiting for the OMR worker (it loads a torch model — up to 2 minutes)…"
  "${COMPOSE[@]}" --profile omr up -d --wait worker \
    || compose_fail "the OMR worker did not become healthy."
  ok "OMR worker is healthy"
fi

if [[ $BACKEND_IN_DOCKER -eq 1 ]]; then
  info "waiting for the backend container (first run compiles the project)…"
  "${COMPOSE[@]}" --profile backend up -d --wait backend \
    || compose_fail "the backend container did not become healthy."
  ok "backend container is healthy"
fi

# ── 4. Host processes ────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR" "$PID_DIR"

STARTED_ANYTHING=0

start_host_process() {
  local name="$1" dir="$2"; shift 2
  local log="$LOG_DIR/$name.log" pidfile="$PID_DIR/$name.pid"

  if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    info "$name is already running (pid $(cat "$pidfile"))"
    return
  fi

  STARTED_ANYTHING=1

  # setsid gives the child its own process group. Without it a background job
  # inherits this shell's group, and tools/dev-down.sh killing that group would
  # take the user's terminal with it.
  if command -v setsid >/dev/null 2>&1; then
    ( cd "$dir" && exec setsid "$@" ) >"$log" 2>&1 &
  else
    ( cd "$dir" && exec "$@" ) >"$log" 2>&1 &
  fi
  local pid=$!
  echo "$pid" >"$pidfile"
  ok "$name started (pid $pid) → $log"
}

# Compose reads .env itself; a host process does not. Parsed line by line rather
# than sourced, because .env legitimately contains values with spaces and
# parentheses (MUSICBRAINZ_USER_AGENT) that would be a bash syntax error.
export_env_file() {
  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" != *=* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    # Strip a trailing inline comment, then surrounding quotes.
    value="${value%%[[:space:]]#*}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    [[ "$value" == \"*\" || "$value" == \'*\' ]] && value="${value:1:${#value}-2}"
    export "$key=$value"
  done < "$1"
}

if [[ $INFRA_ONLY -eq 0 ]]; then
  bold "Starting application"

  export_env_file "$REPO_ROOT/.env"
  export SPRING_PROFILES_ACTIVE=local

  if [[ $BACKEND_IN_DOCKER -eq 0 ]]; then
    start_host_process backend "$REPO_ROOT/backend" \
      ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=local
  fi

  start_host_process frontend "$REPO_ROOT/frontend" npm start

  if [[ $STARTED_ANYTHING -eq 1 ]]; then
    info "backend and frontend compile in the background — give them ~60s."
  fi
fi

# ── 5. Where everything lives ────────────────────────────────────────────────
cat <<EOF

$(bold "PiLearn is up")

  Frontend        http://localhost:${FRONTEND_PORT_HOST}
  Backend API     http://localhost:${BACKEND_PORT_HOST}/api/v1
  OMR worker      http://localhost:${WORKER_PORT_HOST}/docs
  MinIO console   http://localhost:${MINIO_CONSOLE_PORT_HOST}
  Postgres        postgresql://localhost:${DB_PORT_HOST}/pianoml

$(bold "Logs")

  Backend         tail -f .dev/logs/backend.log
  Frontend        tail -f .dev/logs/frontend.log
  Worker          ${COMPOSE[*]} logs -f worker
  Everything      ${COMPOSE[*]} logs -f

$(bold "Try it")

  Open http://localhost:${FRONTEND_PORT_HOST} and drop a PDF on the hero panel. No account
  needed — anonymous uploads are attached to a guest session.

  Stop everything:  tools/dev-down.sh

EOF
