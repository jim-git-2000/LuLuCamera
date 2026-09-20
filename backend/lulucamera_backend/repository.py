from __future__ import annotations

import hashlib
import sqlite3
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

ACTIVE_STATUSES = ("queued", "running")
TERMINAL_STATUSES = ("completed", "failed", "cancelled", "expired")


def utc_now() -> str:
    return datetime.now(UTC).isoformat()


def owner_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Job:
    id: str
    owner_hash: str
    idempotency_key: str
    status: str
    original_path: str
    mask_path: str
    metadata_path: str
    reference_path: str | None
    result_path: str | None
    error_code: str | None
    retryable: int
    created_at: str
    updated_at: str
    expires_at: str

    def public_dict(self) -> dict[str, Any]:
        data = asdict(self)
        for private in ("owner_hash", "original_path", "mask_path", "metadata_path", "reference_path", "result_path"):
            data.pop(private)
        data["retryable"] = bool(data["retryable"])
        data["result_url"] = f"/generations/{self.id}/result" if self.status == "completed" else None
        return data


class JobRepository:
    def __init__(self, database: Path, ttl_hours: int = 24):
        self.database = database
        self.ttl_hours = ttl_hours
        database.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS generation_jobs (
                    id TEXT PRIMARY KEY,
                    owner_hash TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    status TEXT NOT NULL,
                    original_path TEXT NOT NULL,
                    mask_path TEXT NOT NULL,
                    metadata_path TEXT NOT NULL,
                    reference_path TEXT,
                    result_path TEXT,
                    error_code TEXT,
                    retryable INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    UNIQUE(owner_hash, idempotency_key)
                )
                """
            )

    def create(
        self,
        job_id: str,
        owner: str,
        idempotency_key: str,
        original_path: str,
        mask_path: str,
        metadata_path: str,
        reference_path: str | None,
    ) -> tuple[Job, bool]:
        now = utc_now()
        expires = (datetime.now(UTC) + timedelta(hours=self.ttl_hours)).isoformat()
        with self._connect() as connection:
            try:
                connection.execute(
                    """
                    INSERT INTO generation_jobs
                    (id, owner_hash, idempotency_key, status, original_path, mask_path,
                     metadata_path, reference_path, created_at, updated_at, expires_at)
                    VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (job_id, owner, idempotency_key, original_path, mask_path, metadata_path,
                     reference_path, now, now, expires),
                )
                created = True
            except sqlite3.IntegrityError:
                row = connection.execute(
                    "SELECT * FROM generation_jobs WHERE owner_hash = ? AND idempotency_key = ?",
                    (owner, idempotency_key),
                ).fetchone()
                if row is None:
                    raise
                return Job(**dict(row)), False
        return self.get(job_id), created

    def get(self, job_id: str) -> Job:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM generation_jobs WHERE id = ?", (job_id,)).fetchone()
        if row is None:
            raise KeyError(job_id)
        return Job(**dict(row))

    def get_owned(self, job_id: str, owner: str) -> Job:
        job = self.get(job_id)
        if job.owner_hash != owner:
            raise KeyError(job_id)
        return job

    def get_by_idempotency(self, owner: str, idempotency_key: str) -> Job | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM generation_jobs WHERE owner_hash = ? AND idempotency_key = ?",
                (owner, idempotency_key),
            ).fetchone()
        return Job(**dict(row)) if row is not None else None

    def transition(
        self,
        job_id: str,
        status: str,
        *,
        result_path: str | None = None,
        error_code: str | None = None,
        retryable: bool = False,
        allowed_from: tuple[str, ...] | None = None,
    ) -> bool:
        clauses = ["id = ?"]
        values: list[Any] = [status, result_path, error_code, int(retryable), utc_now(), job_id]
        if allowed_from:
            clauses.append(f"status IN ({','.join('?' for _ in allowed_from)})")
            values.extend(allowed_from)
        with self._connect() as connection:
            cursor = connection.execute(
                f"""
                UPDATE generation_jobs
                SET status = ?, result_path = COALESCE(?, result_path), error_code = ?,
                    retryable = ?, updated_at = ?
                WHERE {' AND '.join(clauses)}
                """,
                values,
            )
            return cursor.rowcount == 1

    def active_count(self) -> int:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS count FROM generation_jobs WHERE status IN ('queued', 'running')"
            ).fetchone()
        return int(row["count"])

    def recover_interrupted(self) -> int:
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE generation_jobs SET status = 'failed', error_code = 'WORKER_INTERRUPTED',
                    retryable = 1, updated_at = ? WHERE status = 'running'
                """,
                (utc_now(),),
            )
            return cursor.rowcount

    def expire_due(self) -> list[Job]:
        now = utc_now()
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM generation_jobs WHERE expires_at < ? AND status != 'expired'", (now,)
            ).fetchall()
            connection.execute(
                "UPDATE generation_jobs SET status = 'expired', updated_at = ? WHERE expires_at < ?",
                (now, now),
            )
        return [Job(**dict(row)) for row in rows]
