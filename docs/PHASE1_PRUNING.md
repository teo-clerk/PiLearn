# PHASE1_PRUNING — Dead Code & Dependency Removal

Execution plan for the Phase 1 teardown (roadmap tasks **P1-T14 … P1-T18**).

**Do not run this before the baseline commit exists.** Every deletion below is recoverable from
git history — that is what makes it safe to be aggressive. Without git, it is not.

---

## 0. Corrections to the audit

Re-verification against templates, selectors and NgModule declarations changed two of the
audit's dependency claims. The corrected list is what this document uses.

| Audit said | Reality | Action |
|---|---|---|
| `nouislider` droppable | **2 live call sites**: `workbench.component.ts` *and* `browse.component.ts` | **Keep.** Defer to Phase 4 (P4-T11) |
| `wnumb` / `@types/wnumb` droppable | Same 2 call sites | **Keep.** Defer to Phase 4 |
| — | `lodash` — **0 mentions** anywhere | **Drop** (newly found) |
| — | `ts-md5` — **0 mentions** | **Drop** (newly found) |
| — | `midi-writer-js` — **0 mentions** | **Drop** (newly found) |
| `vexflow` droppable | True, but *only after* `model.ts` is edited (§2.2) | Drop, with a code change |

The audit's "6 droppable dependencies" figure still holds — it is a different six.

Component death was re-confirmed three ways: no `.ts` import, **no selector usage in any
`.html`**, and no NgModule declaration. The earlier check only grepped `.ts`.

---

## 1. Pre-flight

```bash
cd /path/to/PiLearn
git switch -c chore/phase1-pruning

# Record the pre-prune state so the diff is meaningful
cd frontend && npm ci
npx ng build --configuration=production --no-prerender 2>&1 | tail -5
du -sh dist/ > /tmp/bundle-before.txt
cd ../backend && ./mvnw -B -q verify
```

Both must be **green before you delete anything**. If the build is already broken, fix that
first — otherwise you cannot attribute a later failure to the pruning.

---

## 2. Frontend pruning

### 2.1 The dead VexFlow subtree

One connected component, reachable from nothing:

```
AnimatedScoreComponent  (unrouted, no selector usage, not in any NgModule)
        └── EngravingService
                ├── HandDetectorService
                └── rest-filler.ts
```

Plus two more orphan components found by the same selector sweep.

| Path | LOC | Evidence of death |
|---|---|---|
| `app/desktop/components/animated-score/` | ~99 + tpl/css/spec | No route, no `<app-animated-score>` in any template, no NgModule entry |
| `app/desktop/components/pianoman/` | ~40 + tpl/css/spec | No `<app-pianoman>` anywhere |
| `app/desktop/components/svg-icon/` | ~30 + tpl/css/spec | No `<app-svg-icon>` anywhere; superseded by `@ng-icons` |
| `app/desktop/service/engraving.service.ts` | 405 | Sole importer is `animated-score` |
| `app/desktop/service/engraving.service.spec.ts` | ~20 | Tests a deleted file |
| `app/desktop/service/hand-detector.service.ts` | 475 | Sole importer is `engraving.service` |
| `app/desktop/service/hand-detector.service.spec.ts` | ~20 | Tests a deleted file |
| `app/desktop/service/rest-filler.ts` | ~60 | Sole importer is `engraving.service` |

**Total: ~1,150 LOC of source plus templates, styles and specs.**

> **On `hand-detector.service.ts`.** This file encodes real musical knowledge — onset grouping,
> split-point penalties, hand-span constraints — that Phase 2 needs for `ScoreDocument` hand
> assignment (`DATA_PIPELINE` §P7). Roadmap task **P1-T14** says port it to Python *before*
> deleting.
>
> Now that git exists, that ordering is a convenience, not a safety requirement: the file is
> permanently recoverable from history. Delete it now and record the retrieval command in the
> P1-T14 ticket:
>
> ```
> git show <baseline-sha>:frontend/src/app/desktop/service/hand-detector.service.ts
> ```
>
> Put that exact line in the ticket body. A porting task that cannot find its source is how
> knowledge actually gets lost.

### 2.2 Code change required before dropping `vexflow`

After the subtree is gone, one `import type` from `vexflow` survives in `model.ts`. It backs
`StaveAndStaveNotesPair`, which reaches `PlayConfiguration.staveAndStaveNotesPair` — a field that
is **written once and never read** (`workbench.component.ts:125` sets it to `[]`).

Three edits:

**`frontend/src/app/desktop/model/model.ts`** — delete the import and the interface:

```diff
-import type { Stave, StaveNote } from "vexflow";
 import type { Note } from '@tonejs/midi/dist/Note';
 import type * as Midi from '@tonejs/midi';
 import type { ReducedFraction } from "./reduced-fraction";
@@
-export interface StaveAndStaveNotesPair {
-  xPositionsBass: number[];
-  xPositionsTreble: number[];
-  staveNotesTreble: StaveNote[];
-  staveNotesBass: StaveNote[];
-  staveTreble: Stave;
-  staveBass: Stave;
-  midiNotesTreble: Array<Note[]>;
-  midiNotesBass: Array<Note[]>;
-}
-
```

**`model.ts`** — drop the vestigial field from `PlayConfiguration`:

```diff
   scoreRange: [number, number];
   isLoop: boolean;
-  staveAndStaveNotesPair: StaveAndStaveNotesPair[];
   accompaniment: Midi.Midi | null;
```

**`frontend/src/app/desktop/components/workbench/workbench.component.ts:125`** — drop the initialiser:

```diff
     isLoop: false,
-    staveAndStaveNotesPair: [],
     accompaniment: null,
```

`DurationDetection` in `model.ts` is also unreferenced — leave it for now; it costs nothing and
is unrelated to this dependency.

### 2.3 Dependencies to drop

All verified at **zero import sites** across `.ts` and `.html`:

| Package | Section | Evidence |
|---|---|---|
| `vexflow` | dependencies | Last importer removed by §2.1 + §2.2 |
| `lodash` | dependencies | 0 mentions |
| `@types/lodash` | devDependencies | Types for the above |
| `ts-md5` | dependencies | 0 mentions |
| `midi-writer-js` | dependencies | 0 mentions |
| `axios` | dependencies | 0 mentions — all HTTP goes through Angular `HttpClient` |
| `@criblinc/docker-names` | dependencies | 0 mentions |

**Keep** (contrary to the audit): `nouislider`, `wnumb`, `@types/wnumb` — live in both
`workbench` and `browse`. They go in Phase 4 when those components are decomposed.

### 2.4 Also move misplaced `@types/*`

`package.json` lists these under `dependencies`; they belong in `devDependencies`:
`@types/compression`, `@types/marked`, `@types/webmidi`.

Note `@types/webmidi` becomes removable entirely at **P4-T19** (the `webmidi` v3 upgrade ships
its own types). Not now.

---

## 3. Backend pruning

### 3.1 Duplicate annotation API

`pom.xml` declares both `jakarta.annotation-api` and `javax.annotation-api`. The OpenAPI
generator is configured with `useJakartaEe=true`, so generated sources use `jakarta.*`; no
hand-written source imports either namespace.

```diff
     <dependency>
       <groupId>jakarta.annotation</groupId>
       <artifactId>jakarta.annotation-api</artifactId>
       <version>3.0.0</version>
     </dependency>
-    <dependency>
-      <groupId>javax.annotation</groupId>
-      <artifactId>javax.annotation-api</artifactId>
-      <version>1.3.2</version>
-    </dependency>
```

> **Verify, do not assume.** `target/` has never been built in this workspace, so the generated
> sources have not been inspected. Drop `javax.annotation-api` first and run `./mvnw -B verify`.
> If generated code references `javax.annotation.Generated`, restore it and instead investigate
> why `useJakartaEe` is not taking effect.

### 3.2 Legacy scripts

| Path | Reason |
|---|---|
| `backend/scripts/midi2pack1.sh` | Duplicate variant of `midi2pack.sh` |
| `backend/scripts/test.sh` | Ad-hoc scratch script |
| `backend/scripts/convert.py` | Superseded by the pack scripts |

Do **not** touch `pdf2pack.sh`, `musicxml2pack.sh`, `image2pack.sh` or the `extract_*.py` /
`get_metadata.py` / `has_*.py` scripts — the baseline harness measures them, and Phase 2 ports
their logic.

### 3.3 Test stubs

21 of the 31 frontend spec files are 23-line `should create` stubs providing no coverage while
slowing the suite. Delete the stubs; **keep** the three with real assertions:
`musicbrainz.service.spec.ts`, `loading.service.spec.ts`, `link.component.spec.ts`.

The script below identifies stubs by line count (≤ 30) rather than by a hardcoded list, so it
stays correct if the set has drifted.

---

## 4. Execution script

Saved at `tools/phase1-prune.sh`. It is **gated**: it verifies the tree is clean, deletes in
dependency order, and stops at the first failed build.

```bash
./tools/phase1-prune.sh --dry-run     # list what would change
./tools/phase1-prune.sh               # execute, with build gates
```

The manual `model.ts` / `workbench.component.ts` edits from §2.2 are **not** automated — a
script doing surgical TypeScript edits is more risk than value. The script checks they were made
and refuses to drop `vexflow` until they are.

---

## 5. Verification

Run after each stage; never batch a failure.

```bash
# ── Frontend ────────────────────────────────────────────────────────────────
cd frontend

npm ci                                   # clean install against the pruned package.json
npx tsc --noEmit -p tsconfig.app.json    # unresolved imports surface here first
npx ng build --configuration=production --no-prerender
npx ng build                             # dev config too — different optimiser path
npm test -- --watch=false                # surviving specs must pass
npx biome ci .

du -sh dist/ && cat /tmp/bundle-before.txt   # expect a measurable drop

# ── Backend ─────────────────────────────────────────────────────────────────
cd ../backend
./mvnw -B clean verify                   # clean: forces OpenAPI regeneration
./mvnw -B dependency:analyze             # reports further unused declarations

# ── SSR (separate build path — Angular SSR breaks in ways CSR does not) ─────
cd ../frontend
npx ng build --configuration=production   # produces the server bundle
node dist/piano-ml/server/server.mjs &
sleep 5 && curl -fsS http://localhost:4000/ >/dev/null && echo "SSR ok"
kill %1
```

### What a failure means

| Failure | Likely cause | Fix |
|---|---|---|
| `TS2307: Cannot find module 'vexflow'` | §2.2 edits not applied | Apply them, then re-drop |
| `NG8001: '<app-…>' is not a known element` | A template still references a deleted component | The selector sweep missed a dynamic usage — restore and investigate |
| `Cannot find module 'lodash'` | A transitive dep expected it hoisted | Reinstate as an explicit dependency; note it in the PR |
| `package.json` and lockfile disagree | Edited `package.json` by hand without reinstalling | `rm -rf node_modules package-lock.json && npm install` |
| `javax.annotation.Generated not found` | `useJakartaEe` not applied to generated sources | Restore `javax.annotation-api`; open a follow-up |
| SSR boot fails, CSR fine | A deleted file was imported by a server-only path | Check `src/server.ts` and `main.server.ts` |

---

## 6. Commit sequence

Small, independently revertible commits. If something surfaces in a week, you want to revert one
of these, not all of them.

```bash
git commit -m "refactor: remove unrouted animated-score, pianoman and svg-icon components"
git commit -m "refactor: remove dead engraving, hand-detector and rest-filler services

Reachable only from AnimatedScoreComponent, itself unrouted. The hand-splitting
heuristic in hand-detector.service.ts is required for Phase 2 hand assignment
(DATA_PIPELINE P7) and is recoverable via:
  git show <baseline-sha>:frontend/src/app/desktop/service/hand-detector.service.ts
Tracked as P1-T14."

git commit -m "refactor: drop unused StaveAndStaveNotesPair from the play configuration"
git commit -m "chore: drop 6 unused frontend dependencies

vexflow, lodash, @types/lodash, ts-md5, midi-writer-js, axios, @criblinc/docker-names.
nouislider and wnumb are retained — still used by workbench and browse until P4-T11."

git commit -m "chore: move @types packages to devDependencies"
git commit -m "chore: remove duplicate javax.annotation-api and legacy pack scripts"
git commit -m "test: remove 21 placeholder spec files providing no coverage"
```

---

## 7. Expected outcome

| Metric | Before | After |
|---|---|---|
| Frontend app LOC (excl. generated) | 21,741 | ~20,400 |
| Frontend runtime dependencies | 43 | 37 |
| Frontend spec files | 31 | 8 |
| Backend `pom.xml` dependencies | 24 | 23 |
| `backend/scripts/` entries | 22 | 19 |

Bundle-size reduction is real but modest — `vexflow` and `lodash` were tree-shaken from the
production build already. **The win is comprehension, not bytes:** ~1,150 fewer lines that a
future reader has to determine are irrelevant.

---

## 8. Explicitly out of scope

Deferred deliberately; do not expand this PR:

- `cursor.service.ts` (1,101 LOC) → **P2-T22**, after server-side alignment lands
- `workbench.component.ts` (1,009 LOC) → **P4-T11**
- `browse.component.ts` (1,011 LOC) → **P4-T11**
- `nouislider` / `wnumb` removal → **P4-T11**, with the components that use them
- `webmidi` v2 → v3 → **P4-T19**
- Karma → Vitest migration → **P1-T19** (separate PR; touches config, not source)
- The `@jesperdj/pianokeys` unpinned git dependency → **P4-T18**
