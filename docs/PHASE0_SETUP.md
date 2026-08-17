# PHASE0_SETUP — Repository Lockdown Runbook

Operational runbook for Phase 0: local environment setup, secret handling and rotation, and
running the OMR baseline harness.

Companion documents: [`AUDIT_AND_REFACTOR.md`](./AUDIT_AND_REFACTOR.md) ·
[`IMPLEMENTATION_ROADMAP.md`](./IMPLEMENTATION_ROADMAP.md) ·
[`../tools/omr-baseline/README.md`](../tools/omr-baseline/README.md)

---

## 1. Prerequisites

| Tool | Version | Check | Install |
|---|---|---|---|
| Docker + Compose v2 | 24+ | `docker compose version` | [docs.docker.com](https://docs.docker.com/engine/install/) |
| Java | **21** (Temurin) | `java -version` | `mise use -g java@temurin-21` or SDKMAN |
| Maven | 3.9+ | `mvn -v` | use `./mvnw` (§3) once generated |
| Node.js | 22 LTS | `node -v` | `mise use -g node@22` or nvm |
| Python | 3.11+ | `python3 -V` | system package |
| poppler-utils | any | `pdfinfo -v` | `apt install poppler-utils` / `pacman -S poppler` |

> **Java version matters.** `pom.xml` targets `java.version=21`. A newer JDK (23, 25) will
> compile but is not what CI runs, and Lombok in particular breaks on JDK majors it has not
> shipped support for. Pin 21 locally.

```bash
# mise (recommended — reads .tool-versions if present)
mise use -g java@temurin-21 node@22 python@3.11
java -version   # expect: openjdk version "21.x"
```

---

## 2. First-time setup

```bash
git clone <repo-url> PiLearn && cd PiLearn
cp .env.example .env
```

### Generate the required secrets

```bash
# JWT signing key — must be >= 64 chars. The app refuses to start without it.
echo "JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')"

# Storage + database credentials (MinIO requires >= 8 chars for the secret key)
echo "DB_PASSWORD=$(openssl rand -hex 16)"
echo "STORAGE_ACCESS_KEY=$(openssl rand -hex 8)"
echo "STORAGE_SECRET_KEY=$(openssl rand -hex 24)"
```

Paste each into `.env`. Then:

```bash
docker compose up -d                    # postgres + minio + bucket
docker compose ps                       # both healthy

cd backend
set -a && source ../.env && set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

cd ../frontend && npm ci && npm start
```

### Verifying the lockdown works

The fail-fast contract should be observable. Blank the key and confirm the app refuses to boot:

```bash
cd backend
JWT_SECRET="" ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# expect startup failure: "JWT_SECRET must be set; generate one with: openssl rand -base64 64"

JWT_SECRET="supersecret" ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# expect: IllegalStateException — "JWT_SECRET is set to a known placeholder value"
```

If either of these *starts successfully*, the lockdown has regressed. `JwtPropertiesTest`
covers this in CI.

---

## 3. Maven wrapper

The repo has no `mvnw`, so builds depend on whatever Maven the developer happens to have. The
wrapper pins the Maven version in-repo and removes Maven from the prerequisite list.

### Generate it (one-time, needs a system Maven present)

```bash
cd backend
mvn -N wrapper:wrapper -Dmaven=3.9.9 -Dtype=bin
```

This writes:

```
backend/mvnw
backend/mvnw.cmd
backend/.mvn/wrapper/maven-wrapper.properties
```

### If no system Maven is available

Fetch the wrapper files directly:

```bash
cd backend
mkdir -p .mvn/wrapper
BASE="https://raw.githubusercontent.com/apache/maven-wrapper/maven-wrapper-3.3.2/maven-wrapper-distribution/src/resources"
curl -fsSL -o mvnw     "$BASE/mvnw"
curl -fsSL -o mvnw.cmd "$BASE/mvnw.cmd"
chmod +x mvnw

cat > .mvn/wrapper/maven-wrapper.properties <<'EOF'
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
EOF
```

### Pin the toolchain to Java 21

Add to `backend/.mvn/jvm.config` so every invocation is consistent:

```bash
echo "-Dfile.encoding=UTF-8" > backend/.mvn/jvm.config
```

And record the expected JDK for humans and CI:

```bash
cat > .tool-versions <<'EOF'
java temurin-21.0.5+11
nodejs 22.11.0
python 3.11.9
EOF
```

### Verify and commit

```bash
cd backend
./mvnw -v                # must report Apache Maven 3.9.9 and Java 21
./mvnw -B verify

git add mvnw mvnw.cmd .mvn/ ../.tool-versions
git commit -m "build: add Maven wrapper pinned to 3.9.9 for reproducible builds"
```

> **`.gitignore` note.** The root ignore file has `*.jar` with a `!maven-wrapper.jar` exception,
> so `.mvn/wrapper/maven-wrapper.jar` is committable if your wrapper variant downloads one.
> Maven Wrapper 3.3.x defaults to the `script` type, which needs no jar at all — prefer that.
> Confirm with `git status --short backend/.mvn/` before committing.

---

## 4. Secret rotation

### 4.1 What is in scope

| Secret | Where it lives | Blast radius on rotation |
|---|---|---|
| `JWT_SECRET` | env only | **All users logged out.** Every issued token becomes invalid. |
| `DB_PASSWORD` | env + Postgres role | Backend restart required. |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | env + R2/MinIO | Backend restart; in-flight uploads fail. |
| `YOUTUBE_API_KEY` | env | YouTube lookup degrades gracefully; no outage. |
| GCP service-account key | file or workload identity | Ingestion jobs fail until replaced. |

### 4.2 Local development

Zero ceremony — nothing depends on continuity:

```bash
# Edit .env with a fresh value, then:
docker compose down -v          # -v also drops the DB, forcing a clean Liquibase run
docker compose up -d
```

Clear browser storage afterwards (the old JWT will sit in local storage and produce confusing
401s): DevTools → Application → Storage → Clear site data.

### 4.3 Staging and production — JWT

Rotating the signing key invalidates every session at once. There is no key-overlap support in
the current `JwtTokenProvider` (single `Algorithm` instance), so this is a hard cutover.

```bash
# 1. Generate
NEW_SECRET="$(openssl rand -base64 64 | tr -d '\n')"

# 2. Store it in the platform secret manager — never in a file, never in shell history
gcloud secrets versions add pilearn-jwt-secret --data-file=- <<< "$NEW_SECRET"

# 3. Roll the service so instances pick up the new version
gcloud run services update pilearn-api \
    --update-secrets=JWT_SECRET=pilearn-jwt-secret:latest \
    --region="$GCLOUD_LOCATION"

# 4. Verify the new instance is healthy BEFORE announcing
curl -fsS https://api.example.org/actuator/health

# 5. Confirm old tokens are rejected
curl -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $OLD_TOKEN" \
     https://api.example.org/account/userinfo    # expect 401
```

**Announce before rotating.** Users will be logged out mid-session; a practice attempt in flight
will fail to submit. Rotate during a low-traffic window.

**Rotate immediately if:** the key ever appeared in a commit, a log, a screenshot, a support
ticket, or a CI build log.

> **Historical note.** Before Phase 0, the key `your-super-secret-key-that-is-long-enough` was
> committed in `application.properties`, and `JwtTokenProvider` carried a second inline fallback
> of `'supersecret'`. Any environment that ever ran that build must be treated as compromised and
> rotated, even though the repository had no git history at the time.

### 4.4 Staging and production — database

```bash
# 1. Set the new password on the role
psql "$ADMIN_URL" -c "ALTER ROLE pilearn WITH PASSWORD '$NEW_DB_PASSWORD';"

# 2. Update the secret and roll the service
gcloud secrets versions add pilearn-db-password --data-file=- <<< "$NEW_DB_PASSWORD"
gcloud run services update pilearn-api --update-secrets=DB_PASSWORD=pilearn-db-password:latest

# 3. Confirm connectivity
curl -fsS https://api.example.org/actuator/health | jq '.components.db'
```

Postgres does not drop existing connections when a role password changes, so running instances
keep working until they reconnect — the rollout is effectively zero-downtime.

### 4.5 Rotation checklist

- [ ] New value generated with a CSPRNG (`openssl rand`), never typed by hand
- [ ] Stored in the platform secret manager, not in a file or env file on disk
- [ ] Old value revoked / superseded, not merely replaced in one place
- [ ] Service rolled and health-checked
- [ ] Old credential confirmed rejected
- [ ] Rotation recorded (date, reason, operator) in the ops log
- [ ] If the leak was in git: history rewrite considered (`git filter-repo`) **in addition to**
      rotation — rotation is mandatory, history rewrite is cosmetic

---

## 5. Running the OMR baseline

Full detail in [`tools/omr-baseline/README.md`](../tools/omr-baseline/README.md); this is the
operator summary.

### 5.1 What it is for

It measures the **current legacy pipeline** so Phase 2's rewrite can be judged against a number.
It answers two separate questions:

- *Did a change break recognition?* → golden snapshot diff, runs unattended in CI
- *Is recognition any good?* → accuracy against hand-entered ground truth, needs a human once

### 5.2 First run

```bash
./tools/omr-baseline/run-baseline.sh
```

The first invocation builds the OMR toolchain image: CPU PyTorch, MuseScore 3, homr, relieur,
pianoplayer. **20–40 minutes, ~6 GB.** Subsequent runs reuse the image.

### 5.3 Reading the output

```bash
cat tools/omr-baseline/reports/<run-id>/summary.json
```

```json
{
  "fixtureCount": 10,
  "ok": 9,
  "failed": 1,
  "silentPartialFailures": 2,
  "fixtures": [
    { "id": "zimmer-interstellar", "status": "OK", "measures": 48, "notes": 612, "accuracy": null }
  ]
}
```

| Field | How to read it |
|---|---|
| `ok` / `failed` | Did the pipeline produce an archive at all |
| **`silentPartialFailures`** | **The number that matters.** Runs that exited 0 while dropping pages — the score is short and nothing downstream knows. Direct evidence for the P2 fix. |
| `measures` / `notes` | Recognised structure. Compare against the printed score. |
| `accuracy` | `null` until ground truth is filled in (§5.5) |

Per-fixture detail lives in `reports/<run-id>/<fixture-id>.json`; raw pipeline output in
`reports/<run-id>/logs/`.

**Investigating a failure:**

```bash
RUN=<run-id>; FIX=<fixture-id>
jq '.execution' tools/omr-baseline/reports/$RUN/$FIX.json
tail -50 tools/omr-baseline/reports/$RUN/logs/$FIX.stderr.log
```

### 5.4 Locking in the baseline

Once a run looks representative:

```bash
python3 tools/omr-baseline/harness/compare.py promote --run <run-id>
git add tools/omr-baseline/golden/
git commit -m "test: lock in OMR baseline from legacy pipeline"
```

Thereafter:

```bash
./tools/omr-baseline/run-baseline.sh --check      # exit 1 on regression
```

A regression report localises the change:

```
chopin-prelude-e-minor:
  musicxml.measure_count: 25 -> 24
  musicxml.measure_note_counts: [...] first divergence at measure 3: 16 -> 12
```

Only re-promote when the change is intentional **and reviewed**. Re-promoting to make a red
build green discards the entire value of the harness.

### 5.5 Filling in ground truth (task P1-T13)

Regression checking works without it. Knowing whether recognition is any *good* does not.

For each fixture, open the PDF, read the printed score, fill its `groundTruth` block in
`tools/omr-baseline/fixtures/manifest.json`:

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

`keySignature` is the **sharp count as a signed integer string** (music21 convention): `-2` =
B♭ major / G minor, `0` = C major / A minor, `1` = G major / E minor. Count measures as printed,
including any pickup, excluding repeat expansion.

Roll the resulting `accuracy.overall` figures into `docs/OMR_BASELINE.md`. **That table is the
number Phase 2 must beat.**

### 5.6 Known corpus gaps

- **7 of 10 fixtures are single-page** — the `relieur` merge stage and the silent partial-failure
  path are barely exercised. Add an 8+ page score before trusting merge numbers.
- **All fixtures are born-digital engraving** — no scans, which is the harder real-world case and
  where homr and Audiveris diverge most. Add one scan before Phase 2 concludes, or the two-engine
  arbitration (P2-T08) gets tuned on the easy case.

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Backend exits at startup: "JWT_SECRET must be set" | `.env` not sourced | `set -a && source ../.env && set +a` |
| "JWT_SECRET is set to a known placeholder value" | Copied an example value | Generate a real key (§2) |
| "must be at least 64 characters" | Key too short | `openssl rand -base64 64` |
| Compose fails: "DB_PASSWORD must be set in .env" | Empty value in `.env` | Fill it in; compose reads the repo-root `.env` |
| Liquibase checksum errors | Changed an applied changeset | Never edit applied changesets — add a new one. Locally: `docker compose down -v` |
| Frontend 401s after rotation | Stale JWT in local storage | DevTools → Application → Clear site data |
| `run-baseline.sh`: "pdf2pack.sh not present" | Image build incomplete | `docker compose --profile omr up -d --build omr` and watch for errors |
| `run-baseline.sh`: "Scores/ not found" | Corpus absent | It is gitignored by design; fetch it locally |
| Lombok errors on `./mvnw verify` | JDK newer than 21 | Pin Java 21 (§1) |
