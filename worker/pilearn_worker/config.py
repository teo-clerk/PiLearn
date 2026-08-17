"""Worker configuration.

Same rule as the backend: no environment-specific value has a committed default that
would work in production. Storage credentials have no fallback at all — a worker that
silently starts with no bucket is worse than one that refuses to boot.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    # ── Server ───────────────────────────────────────────────────────────────
    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"

    # ── Pipeline ─────────────────────────────────────────────────────────────
    # Root of the legacy toolchain layout: scripts/, homr/, relieur/, pianoplayer/.
    pipeline_root: str = Field(default="/home/appuser", alias="PIPELINE_ROOT")
    work_root: str = Field(default="/home/appuser/work", alias="WORK_ROOT")
    job_timeout_sec: int = Field(default=3600, alias="JOB_TIMEOUT_SEC")
    lease_seconds: int = Field(default=900, alias="LEASE_SECONDS")

    # Bounded because homr holds a torch model in memory and MuseScore spawns an
    # offscreen Qt process: the box runs out of RAM long before it runs out of CPU.
    omr_max_concurrency: int = Field(default=2, ge=1, le=16, alias="OMR_MAX_CONCURRENCY")
    omr_default_engine: str = Field(default="homr", alias="OMR_DEFAULT_ENGINE")
    omr_confidence_threshold: float = Field(
        default=0.60, ge=0.0, le=1.0, alias="OMR_CONFIDENCE_THRESHOLD"
    )

    # Version strings feed the idempotency key and the document's provenance. Bump them
    # when tool behaviour changes, or cached results outlive their validity.
    pipeline_version: str = Field(default="legacy-shell-1", alias="PIPELINE_VERSION")
    analysis_version: str = Field(default="analysis-2026.08", alias="ANALYSIS_VERSION")

    # ── Job state ────────────────────────────────────────────────────────────
    # Unset => in-memory store, single replica only.
    redis_url: str | None = Field(default=None, alias="REDIS_URL")

    # ── Object storage ───────────────────────────────────────────────────────
    storage_endpoint: str | None = Field(default=None, alias="STORAGE_ENDPOINT")
    storage_region: str = Field(default="us-east-1", alias="STORAGE_REGION")
    storage_bucket: str = Field(default="pilearn-media", alias="STORAGE_BUCKET")
    storage_access_key: str | None = Field(default=None, alias="STORAGE_ACCESS_KEY")
    storage_secret_key: str | None = Field(default=None, alias="STORAGE_SECRET_KEY")
    storage_path_style: bool = Field(default=True, alias="STORAGE_PATH_STYLE_ACCESS")

    # Local filesystem fallback, for tests and offline development.
    local_storage_root: str | None = Field(default=None, alias="LOCAL_STORAGE_ROOT")

    @model_validator(mode="after")
    def _require_a_storage_backend(self) -> Settings:
        has_s3 = bool(
            self.storage_endpoint and self.storage_access_key and self.storage_secret_key
        )
        if not has_s3 and not self.local_storage_root:
            raise ValueError(
                "no storage backend configured: set STORAGE_ENDPOINT + STORAGE_ACCESS_KEY "
                "+ STORAGE_SECRET_KEY, or LOCAL_STORAGE_ROOT for local development"
            )
        return self

    @property
    def use_local_storage(self) -> bool:
        return not (
            self.storage_endpoint and self.storage_access_key and self.storage_secret_key
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
