# ─────────────────────────────────────────────────────────────────────────────
# PiLearn OMR worker
#
# Multi-stage, ordered so the expensive layers are the stable ones. Layer sizes:
#   toolchain (torch + MuseScore + homr)   ~5 GB   changes ~never
#   python deps                            ~400 MB changes on a pin bump
#   application code                       ~1 MB   changes every commit
#
# Editing pilearn_worker/ rebuilds only the last layer.
#
# BUILD CONTEXT IS THE REPO ROOT — the image needs both worker/ and backend/scripts:
#   docker build -f worker/Dockerfile -t pilearn/omr-worker:local .
# ─────────────────────────────────────────────────────────────────────────────

# =============================================================================
# Stage 1 — native toolchain
# =============================================================================
FROM debian:12-slim AS toolchain

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    PYTHONUTF8=1 \
    PYTHONUNBUFFERED=1 \
    QT_QPA_PLATFORM=offscreen

RUN apt-get update && apt-get install -y --no-install-recommends \
        locales python3.11 python3-pip python3-venv pipx git curl ca-certificates \
        poppler-utils zip unzip musescore3 \
        openjdk-17-jre-headless \
    && sed -i 's/^# *\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen \
    && locale-gen en_US.UTF-8 \
    && ln -sf /usr/bin/python3.11 /usr/bin/python \
    && rm -rf /var/lib/apt/lists/* && apt-get clean

RUN groupadd --gid 1000 appuser \
    && useradd --uid 1000 --gid appuser --shell /bin/bash --create-home appuser

USER appuser
WORKDIR /home/appuser

# ── Heavy ML layer. CPU-only torch saves ~2 GB over the CUDA wheel; the worker
#    never sees a GPU on Cloud Run.
RUN python -m venv /home/appuser/shared-venv \
    && /home/appuser/shared-venv/bin/pip install --upgrade pip --no-cache-dir \
    && /home/appuser/shared-venv/bin/pip install --no-cache-dir \
        torch --index-url https://download.pytorch.org/whl/cpu \
    && /home/appuser/shared-venv/bin/pip install --no-cache-dir \
        transformers scikit-learn lightgbm safetensors

# ── OMR engines and analysis tools.
#    PINNED TO COMMIT SHAs. The legacy image cloned --depth=1 from default branches,
#    so two builds a week apart produced different recognition and silently moved the
#    golden baseline. Bump these deliberately, with a baseline re-run.
# Resolved 2026-08-18. These were branch names, not SHAs, despite the note above —
# so builds were never actually reproducible, and `relieur` failed outright because
# its default branch is `master`, not `main`.
ARG HOMR_SHA=2d0c0a66b6ebc9a8b3e3e61b3a6be4b0e1701d97
ARG RELIEUR_SHA=6db59c4ea4aef11741cecec253a0622a2933a475
ARG PIANOPLAYER_SHA=6fb0e2114d7b21f75c0be5208172e1243a57323e
ARG AUTOHARMONIZER_SHA=3b3a72179f38e3d98ace1ca9a4682f8663bc90bd
ARG SYLLABUS_SHA=e161954880f50e2ddc226bd066f855ec12424ded

ENV PATH="/home/appuser/.local/bin:${PATH}"
RUN pipx install poetry

RUN git clone https://github.com/liebharc/homr.git \
    && cd homr && git checkout "${HOMR_SHA}" \
    && poetry install --only main \
    && poetry run python -c "from homr.main import main; import sys; sys.argv=['homr','--init']; main()" \
    && rm -rf /home/appuser/homr/.git

# Installed into shared-venv, NOT via `poetry install`.
#
# pdf2pack.sh runs relieur as `~/shared-venv/bin/python ./relieur/relieur/relieur.py`,
# so its dependencies have to be in THAT interpreter. `poetry install` put them in a
# poetry-managed venv nothing ever used, and every real ingestion died at the page-merge
# step with `ModuleNotFoundError: No module named 'musicxml'` — after homr had already
# done the expensive work.
RUN git clone https://github.com/piano-ml/relieur.git \
    && cd relieur && git checkout "${RELIEUR_SHA}" \
    && /home/appuser/shared-venv/bin/pip install --no-cache-dir . \
    && /home/appuser/shared-venv/bin/python -c "import musicxml" \
    && rm -rf /home/appuser/relieur/.git

RUN git clone https://github.com/marcomusy/pianoplayer.git \
    && cd pianoplayer && git checkout "${PIANOPLAYER_SHA}" \
    && /home/appuser/shared-venv/bin/pip install --no-cache-dir . \
    && rm -rf /home/appuser/pianoplayer/.git

RUN git clone https://github.com/piano-ml/autoharmonizer.git \
    && cd autoharmonizer && git checkout "${AUTOHARMONIZER_SHA}" \
    && /home/appuser/shared-venv/bin/pip install --no-cache-dir -r requirements.txt \
    && rm -rf /home/appuser/autoharmonizer/.git

RUN git clone https://github.com/piano-ml/piano-syllabus-classifier.git \
    && cd piano-syllabus-classifier && git checkout "${SYLLABUS_SHA}" \
    && rm -rf /home/appuser/piano-syllabus-classifier/.git

# =============================================================================
# Stage 2 — application
# =============================================================================
FROM toolchain AS app

USER appuser
WORKDIR /home/appuser

# Python deps before source, so a code edit does not reinstall them.
COPY --chown=appuser:appuser worker/pyproject.toml /home/appuser/worker/pyproject.toml
RUN /home/appuser/shared-venv/bin/pip install --no-cache-dir \
        "fastapi==0.118.0" "uvicorn[standard]==0.37.0" "python-multipart==0.0.20" \
        "pydantic==2.12.4" "pydantic-settings==2.7.1" "redis==5.2.1" \
        "music21==10.5.0" "mido==1.3.3" "pypdf==5.1.0" \
        "boto3==1.35.92" "botocore==1.35.92" "httpx==0.28.1" \
        "structlog==24.4.0" "tenacity==9.0.0"

# The legacy shell pipeline the strangler still delegates to (PHASE2_KICKOFF §2.2 step 1).
COPY --chown=appuser:appuser backend/scripts /home/appuser/scripts

COPY --chown=appuser:appuser worker/pilearn_worker /home/appuser/worker/pilearn_worker
RUN /home/appuser/shared-venv/bin/pip install --no-cache-dir --no-deps -e /home/appuser/worker

ENV PIPELINE_ROOT=/home/appuser \
    WORK_ROOT=/home/appuser/work \
    PYTHONPATH=/home/appuser/worker \
    PATH="/home/appuser/shared-venv/bin:${PATH}"

RUN mkdir -p /home/appuser/work

EXPOSE 8000

# Readiness, not just liveness: /health reports whether MuseScore, poppler and the
# pipeline scripts are actually reachable, so a half-built image fails the check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import httpx,sys; r=httpx.get('http://localhost:8000/health',timeout=4); sys.exit(0 if r.json().get('status')=='ok' else 1)"

# One worker per container. Ingestion is CPU-bound and memory-heavy; concurrency is
# controlled inside the app by OMR_MAX_CONCURRENCY, and scaled out by replica count.
CMD ["uvicorn", "pilearn_worker.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
