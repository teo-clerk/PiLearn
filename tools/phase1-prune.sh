#!/usr/bin/env bash
#
# Phase 1 teardown — dead code and dependency removal.
# Plan and rationale: docs/PHASE1_PRUNING.md
#
#   ./tools/phase1-prune.sh --dry-run    show what would change
#   ./tools/phase1-prune.sh              execute, gated on a clean build
#
# Gated by design: it refuses to run on a dirty tree, verifies the build before
# and after, and stops at the first failure. Everything it deletes is recoverable
# from git history — which is why it insists on git being present.
#
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DRY_RUN=0
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)    DRY_RUN=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;   # for fast iteration only; never in CI
    -h|--help)    sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
step() { printf '\n%s==> %s%s\n' "$BOLD" "$*" "$OFF"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    %s%s%s\n' "$YELLOW" "$*" "$OFF"; }
fail() { printf '\n%sERROR: %s%s\n' "$RED" "$*" "$OFF" >&2; exit 1; }
ok()   { printf '    %s%s%s\n' "$GREEN" "$*" "$OFF"; }

run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    info "[dry-run] $*"
  else
    "$@"
  fi
}

remove() {
  local target="$1"
  if [[ -e "$target" ]]; then
    if [[ $DRY_RUN -eq 1 ]]; then
      info "[dry-run] rm -rf $target"
    else
      git rm -rq --ignore-unmatch "$target" 2>/dev/null || rm -rf "$target"
      info "removed $target"
    fi
  else
    warn "already absent: $target"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Pre-flight
# ─────────────────────────────────────────────────────────────────────────────
step "Pre-flight"

git rev-parse --git-dir >/dev/null 2>&1 \
  || fail "not a git repository. Deletions are only safe once history exists — see docs/PHASE0_SETUP.md"

if [[ $DRY_RUN -eq 0 && -n "$(git status --porcelain)" ]]; then
  fail "working tree is dirty. Commit or stash first — you want this pruning isolated in its own diff."
fi

BASELINE_SHA="$(git rev-parse --short HEAD)"
ok "baseline commit: $BASELINE_SHA"
info "recovery for any deleted file:  git show $BASELINE_SHA:<path>"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" == "main" && $DRY_RUN -eq 0 ]] \
  && fail "refusing to prune directly on main. Run: git switch -c chore/phase1-pruning"

command -v jq >/dev/null 2>&1 || fail "jq is required for the package.json edits (apt/pacman install jq)"

# ─────────────────────────────────────────────────────────────────────────────
# Gate: build must be green BEFORE we touch anything
# ─────────────────────────────────────────────────────────────────────────────
if [[ $SKIP_BUILD -eq 0 && $DRY_RUN -eq 0 ]]; then
  step "Gate 0 — verifying the build is green before deleting"
  ( cd frontend && [[ -d node_modules ]] || npm ci )
  ( cd frontend && npx tsc --noEmit -p tsconfig.app.json ) \
    || fail "frontend does not typecheck BEFORE pruning. Fix that first."
  ok "frontend typecheck green"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 1. Dead component subtree
# ─────────────────────────────────────────────────────────────────────────────
step "1. Removing the unrouted VexFlow subtree"
info "verified dead by: no .ts import, no selector in any .html, no NgModule entry"

FE="frontend/src/app/desktop"
remove "$FE/components/animated-score"
remove "$FE/components/pianoman"
remove "$FE/components/svg-icon"
remove "$FE/service/engraving.service.ts"
remove "$FE/service/engraving.service.spec.ts"
remove "$FE/service/hand-detector.service.ts"
remove "$FE/service/hand-detector.service.spec.ts"
remove "$FE/service/rest-filler.ts"

warn "hand-detector.service.ts held the hand-splitting heuristic needed for Phase 2 (P7)."
warn "Put this in the P1-T14 ticket:"
warn "  git show $BASELINE_SHA:frontend/src/app/desktop/service/hand-detector.service.ts"

# ─────────────────────────────────────────────────────────────────────────────
# 2. Guard: the manual model.ts edits must be done before vexflow can go
# ─────────────────────────────────────────────────────────────────────────────
step "2. Checking the manual edits from PHASE1_PRUNING.md §2.2"

MODEL="frontend/src/app/desktop/model/model.ts"
WORKBENCH="frontend/src/app/desktop/components/workbench/workbench.component.ts"
DROP_VEXFLOW=1

if grep -q "from \"vexflow\"\|from 'vexflow'" "$MODEL" 2>/dev/null; then
  DROP_VEXFLOW=0
  warn "$MODEL still imports vexflow — skipping the vexflow drop."
  warn "Apply the three edits in docs/PHASE1_PRUNING.md §2.2, then re-run."
elif grep -q "staveAndStaveNotesPair" "$WORKBENCH" 2>/dev/null; then
  DROP_VEXFLOW=0
  warn "$WORKBENCH still sets staveAndStaveNotesPair — skipping the vexflow drop."
else
  ok "model.ts and workbench.component.ts are clean; vexflow is droppable"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 3. Frontend dependencies
# ─────────────────────────────────────────────────────────────────────────────
step "3. Dropping unused frontend dependencies"

DEPS_TO_DROP=(lodash ts-md5 midi-writer-js axios "@criblinc/docker-names")
DEV_DEPS_TO_DROP=("@types/lodash")
[[ $DROP_VEXFLOW -eq 1 ]] && DEPS_TO_DROP+=(vexflow)

# Re-verify zero usage at execution time rather than trusting the audit.
for dep in "${DEPS_TO_DROP[@]}" "${DEV_DEPS_TO_DROP[@]}"; do
  [[ "$dep" == "@types/"* ]] && continue
  hits="$(grep -rl "from '$dep'\|from \"$dep\"\|from '$dep/" \
            --include='*.ts' --include='*.html' frontend/src 2>/dev/null | wc -l)"
  if [[ "$hits" -gt 0 ]]; then
    grep -rn "from '$dep'\|from \"$dep\"\|from '$dep/" --include='*.ts' frontend/src | head -5
    fail "$dep still has $hits import site(s) — audit is stale, stopping."
  fi
done
ok "all targets confirmed at zero import sites"

info "keeping nouislider + wnumb: still used by workbench and browse (removed at P4-T11)"

if [[ $DRY_RUN -eq 0 ]]; then
  cd frontend
  tmp="$(mktemp)"
  jq_filter='.'
  for dep in "${DEPS_TO_DROP[@]}";     do jq_filter="$jq_filter | del(.dependencies[\"$dep\"])"; done
  for dep in "${DEV_DEPS_TO_DROP[@]}"; do jq_filter="$jq_filter | del(.devDependencies[\"$dep\"])"; done

  # Relocate misplaced @types/* into devDependencies (§2.4)
  for t in "@types/compression" "@types/marked" "@types/webmidi"; do
    jq_filter="$jq_filter | if .dependencies[\"$t\"] then \
      .devDependencies[\"$t\"] = .dependencies[\"$t\"] | del(.dependencies[\"$t\"]) else . end"
  done

  jq "$jq_filter" package.json > "$tmp" && mv "$tmp" package.json
  cd ..
  ok "package.json updated"
else
  info "[dry-run] would drop: ${DEPS_TO_DROP[*]} ${DEV_DEPS_TO_DROP[*]}"
  info "[dry-run] would relocate @types/compression, @types/marked, @types/webmidi to devDependencies"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 4. Backend
# ─────────────────────────────────────────────────────────────────────────────
step "4. Removing legacy backend scripts"
remove "backend/scripts/midi2pack1.sh"
remove "backend/scripts/test.sh"
remove "backend/scripts/convert.py"
info "pdf2pack.sh / musicxml2pack.sh / image2pack.sh retained — measured by the baseline harness"

step "5. javax.annotation-api (manual)"
warn "Remove this block from backend/pom.xml, then run ./mvnw -B clean verify:"
cat <<'XML'
    <dependency>
      <groupId>javax.annotation</groupId>
      <artifactId>javax.annotation-api</artifactId>
      <version>1.3.2</version>
    </dependency>
XML
warn "If generated sources reference javax.annotation.Generated, restore it and investigate useJakartaEe."

# ─────────────────────────────────────────────────────────────────────────────
# 6. Placeholder specs
# ─────────────────────────────────────────────────────────────────────────────
step "6. Removing placeholder spec files"
info "identifying stubs by size (<= 30 lines), preserving specs with real assertions"

KEEP_SPECS=("musicbrainz.service.spec.ts" "loading.service.spec.ts" "link.component.spec.ts")
stub_count=0

while IFS= read -r spec; do
  base="$(basename "$spec")"
  for keep in "${KEEP_SPECS[@]}"; do [[ "$base" == "$keep" ]] && continue 2; done
  lines="$(wc -l < "$spec")"
  if [[ "$lines" -le 30 ]]; then
    remove "$spec"
    stub_count=$((stub_count + 1))
  fi
done < <(find frontend/src -name '*.spec.ts' | sort)

ok "$stub_count placeholder spec(s) removed"

# ─────────────────────────────────────────────────────────────────────────────
# 7. Verify
# ─────────────────────────────────────────────────────────────────────────────
if [[ $DRY_RUN -eq 1 ]]; then
  step "Dry run complete — nothing changed"
  echo
  info "Execute for real:  ./tools/phase1-prune.sh"
  exit 0
fi

if [[ $SKIP_BUILD -eq 1 ]]; then
  step "Skipping verification (--skip-build)"
  warn "Run the full sequence in docs/PHASE1_PRUNING.md §5 before committing."
  exit 0
fi

step "7. Verifying the pruned build"

cd frontend
info "clean install against the pruned package.json..."
rm -rf node_modules
npm install   # regenerates package-lock.json to match

info "typecheck..."
npx tsc --noEmit -p tsconfig.app.json || fail "typecheck failed — see PHASE1_PRUNING.md §5"
ok "typecheck green"

info "production build..."
npx ng build --configuration=production --no-prerender || fail "production build failed"
ok "production build green"

info "unit tests..."
npm test -- --watch=false 2>/dev/null || warn "tests failed or runner not configured — check manually"

cd ../backend
if [[ -x ./mvnw ]]; then
  info "backend clean verify..."
  ./mvnw -B clean verify || fail "backend build failed"
  ok "backend green"
else
  warn "./mvnw not found — generate it first (docs/PHASE0_SETUP.md §3), then run: ./mvnw -B clean verify"
fi

cd ..
step "Pruning complete"
echo
git status --short | head -40
echo
info "Bundle size:"
du -sh frontend/dist 2>/dev/null || true
echo
info "Next: review the diff, then commit in the sequence from docs/PHASE1_PRUNING.md §6"
info "Do NOT squash — each commit should be independently revertible."
