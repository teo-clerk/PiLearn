"""Object storage and ScoreDocument persistence.

Two backends behind one interface: S3-compatible (MinIO locally, Cloudflare R2 in
production) and a local filesystem for tests and offline development.

Key layout — note that raw and derived are SEPARATE prefixes:

    raw/{score_id}/original.{ext}                 immutable, never overwritten
    derived/{score_id}/{revision}/document.json
    derived/{score_id}/{revision}/index.json
    derived/{score_id}/{revision}/score.musicxml
    derived/{score_id}/{revision}/score.mid
    derived/{score_id}/{revision}/confidence.json

The legacy pipeline wrote its result back to the same key it read the upload from
(`WorkloadProcessingService` calls `makeBucketKeyFromScore(score)` for both), so ingesting
a PDF destroyed the PDF. Separating the prefixes is the fix, and `put_raw` refuses to
overwrite so it cannot regress.
"""

from __future__ import annotations

import json
import logging
import shutil
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)


def raw_key(score_id: str, extension: str) -> str:
    return f"raw/{score_id}/original{extension}"


def derived_key(score_id: str, revision: int, name: str) -> str:
    return f"derived/{score_id}/{revision}/{name}"


class ObjectStorage:
    """S3-compatible storage with a local filesystem fallback."""

    def __init__(self, settings: Any) -> None:
        self._settings = settings
        self._local_root: Path | None = None
        self._client = None

        if settings.use_local_storage:
            root = settings.local_storage_root
            if not root:
                raise ValueError("local storage selected but LOCAL_STORAGE_ROOT is unset")
            self._local_root = Path(root)
            self._local_root.mkdir(parents=True, exist_ok=True)
            logger.info("storage: local filesystem at %s", self._local_root)
        else:
            import boto3
            from botocore.config import Config

            self._client = boto3.client(
                "s3",
                endpoint_url=settings.storage_endpoint,
                region_name=settings.storage_region,
                aws_access_key_id=settings.storage_access_key,
                aws_secret_access_key=settings.storage_secret_key,
                config=Config(
                    s3={"addressing_style": "path" if settings.storage_path_style else "auto"},
                    retries={"max_attempts": 3, "mode": "standard"},
                ),
            )
            logger.info(
                "storage: s3 endpoint=%s bucket=%s",
                settings.storage_endpoint,
                settings.storage_bucket,
            )

    @property
    def bucket(self) -> str:
        return self._settings.storage_bucket

    def _path(self, key: str) -> Path:
        assert self._local_root is not None
        return self._local_root / key

    def exists(self, key: str) -> bool:
        if self._local_root is not None:
            return self._path(key).is_file()
        from botocore.exceptions import ClientError

        try:
            self._client.head_object(Bucket=self.bucket, Key=key)  # type: ignore[union-attr]
            return True
        except ClientError:
            return False

    def get_object(self, key: str) -> bytes:
        if self._local_root is not None:
            path = self._path(key)
            if not path.is_file():
                raise FileNotFoundError(key)
            return path.read_bytes()

        from botocore.exceptions import ClientError

        try:
            response = self._client.get_object(Bucket=self.bucket, Key=key)  # type: ignore[union-attr]
            return response["Body"].read()
        except ClientError as exc:
            code = exc.response.get("Error", {}).get("Code")
            if code in ("NoSuchKey", "404"):
                raise FileNotFoundError(key) from exc
            raise

    def put_object(self, key: str, payload: bytes, content_type: str = "application/octet-stream") -> None:
        if self._local_root is not None:
            path = self._path(key)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            return
        self._client.put_object(  # type: ignore[union-attr]
            Bucket=self.bucket, Key=key, Body=payload, ContentType=content_type
        )

    def put_raw(self, score_id: str, extension: str, payload: bytes) -> str:
        """Write the original upload. Refuses to overwrite.

        This is the guard against the legacy defect where the ingestion result clobbered
        the source PDF. The original is the only artefact that cannot be regenerated.
        """
        key = raw_key(score_id, extension)
        if self.exists(key):
            raise FileExistsError(
                f"refusing to overwrite an immutable original at {key}. "
                "Re-ingestion writes a new derived revision, never a new raw object."
            )
        self.put_object(key, payload)
        return key

    def put_file(self, key: str, path: Path, content_type: str = "application/octet-stream") -> None:
        if self._local_root is not None:
            destination = self._path(key)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
            return
        self._client.upload_file(  # type: ignore[union-attr]
            str(path), self.bucket, key, ExtraArgs={"ContentType": content_type}
        )


class DocumentStore:
    """Reads and writes the canonical ScoreDocument."""

    def __init__(self, storage: ObjectStorage, settings: Any) -> None:
        self._storage = storage
        self._settings = settings

    def save(self, score_id: str, revision: int, document: dict) -> str:
        key = derived_key(score_id, revision, "document.json")
        self._storage.put_object(
            key, json.dumps(document, separators=(",", ":")).encode(), "application/json"
        )
        self._write_latest_pointer(score_id, revision)
        return key

    def save_index(self, score_id: str, revision: int, index: dict) -> str:
        key = derived_key(score_id, revision, "index.json")
        self._storage.put_object(
            key, json.dumps(index, separators=(",", ":")).encode(), "application/json"
        )
        return key

    def load(self, score_id: str, revision: int | None = None) -> dict | None:
        resolved = revision if revision is not None else self._latest_revision(score_id)
        if resolved is None:
            return None
        try:
            payload = self._storage.get_object(derived_key(score_id, resolved, "document.json"))
        except FileNotFoundError:
            return None
        return json.loads(payload)

    def _write_latest_pointer(self, score_id: str, revision: int) -> None:
        self._storage.put_object(
            f"derived/{score_id}/latest.json",
            json.dumps({"revision": revision}).encode(),
            "application/json",
        )

    def _latest_revision(self, score_id: str) -> int | None:
        try:
            payload = self._storage.get_object(f"derived/{score_id}/latest.json")
        except FileNotFoundError:
            return None
        return json.loads(payload).get("revision")
