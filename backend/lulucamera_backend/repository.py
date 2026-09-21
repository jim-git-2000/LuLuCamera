from __future__ import annotations

import hashlib
from contextlib import closing
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
        receipt = Path(self.metadata_path).parent / "receipt.json"
        if self.status == "completed":
            import json
            try:
                data.update(json.loads(receipt.read_text()))
            except (OSError, ValueError):
                pass
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
        with closing(self._connect()) as connection, connection:
            connection.execute("CREATE TABLE IF NOT EXISTS cancelled_requests (owner_hash TEXT NOT NULL, idempotency_key TEXT NOT NULL, PRIMARY KEY(owner_hash, idempotency_key))")
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

    def cancel_by_key(self, owner: str, key: str) -> tuple[Job | None, str | None]:
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("INSERT OR IGNORE INTO cancelled_requests VALUES (?, ?)", (owner, key))
            row = connection.execute("SELECT * FROM generation_jobs WHERE owner_hash=? AND idempotency_key=?",
                                     (owner, key)).fetchone()
            if row is None:
                return None, None
            job = Job(**dict(row))
            if job.status != "expired":
                connection.execute("UPDATE generation_jobs SET status='cancelled', error_code=NULL, retryable=0, updated_at=? WHERE id=?",
                                   (utc_now(), job.id))
            return job, job.status

    def create(
        self,
        job_id: str,
        owner: str,
        idempotency_key: str,
        original_path: str,
        mask_path: str,
        metadata_path: str,
        reference_path: str | None,
        max_active_jobs: int = 20,
    ) -> tuple[Job, bool]:
        now = utc_now()
        expires = (datetime.now(UTC) + timedelta(hours=self.ttl_hours)).isoformat()
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT * FROM generation_jobs WHERE owner_hash=? AND idempotency_key=?",
                (owner, idempotency_key),
            ).fetchone()
            if existing:
                return Job(**dict(existing)), False
            if connection.execute("SELECT 1 FROM cancelled_requests WHERE owner_hash=? AND idempotency_key=?",
                                  (owner, idempotency_key)).fetchone():
                from .editing import GenerationError
                raise GenerationError("REQUEST_CANCELLED")
            count = connection.execute("SELECT count(*) FROM generation_jobs WHERE status IN ('queued','running')").fetchone()[0]
            if count >= max_active_jobs:
                from .editing import GenerationError
                raise GenerationError("QUEUE_FULL", True)
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
        with closing(self._connect()) as connection, connection:
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
        with closing(self._connect()) as connection, connection:
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
        with closing(self._connect()) as connection, connection:
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

    def cancel(self, job_id: str) -> str:
        """原子取消并返回此前状态，避免删除仍由 Worker 使用的输入。"""
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT status FROM generation_jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise KeyError(job_id)
            previous = row[0]
            if previous not in ("expired", "cancelled"):
                connection.execute("UPDATE generation_jobs SET status='cancelled', error_code=NULL, retryable=0, updated_at=? WHERE id=?",
                                   (utc_now(), job_id))
            return previous

    def retry(self, job_id: str, limit: int) -> bool:
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT status, retryable FROM generation_jobs WHERE id=?", (job_id,)).fetchone()
            if row is None or row[0] != "failed" or not row[1]:
                return False
            count = connection.execute("SELECT count(*) FROM generation_jobs WHERE status IN ('queued','running')").fetchone()[0]
            if count >= limit:
                from .editing import GenerationError
                raise GenerationError("QUEUE_FULL", True)
            connection.execute("UPDATE generation_jobs SET status='queued', error_code=NULL, retryable=0, updated_at=? WHERE id=?",
                               (utc_now(), job_id))
            return True

    def active_count(self) -> int:
        with closing(self._connect()) as connection, connection:
            row = connection.execute(
                "SELECT COUNT(*) AS count FROM generation_jobs WHERE status IN ('queued', 'running')"
            ).fetchone()
        return int(row["count"])

    def queued_ids(self) -> list[str]:
        with closing(self._connect()) as connection, connection:
            return [row[0] for row in connection.execute("SELECT id FROM generation_jobs WHERE status='queued'")]

    def recover_interrupted(self) -> int:
        with closing(self._connect()) as connection, connection:
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
        with closing(self._connect()) as connection, connection:
            rows = connection.execute(
                "SELECT * FROM generation_jobs WHERE expires_at < ? AND status != 'expired'", (now,)
            ).fetchall()
            connection.execute(
                "UPDATE generation_jobs SET status = 'expired', updated_at = ? WHERE expires_at < ?",
                (now, now),
            )
        return [Job(**dict(row)) for row in rows]
