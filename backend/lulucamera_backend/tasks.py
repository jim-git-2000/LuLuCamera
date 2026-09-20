from __future__ import annotations

import os
from pathlib import Path

from huey import SqliteHuey

from .config import settings
from .generators import create_generator
from .repository import JobRepository

repository = JobRepository(settings.storage_dir / "jobs.sqlite3", settings.ttl_hours)
huey = SqliteHuey(
    "lulucamera",
    filename=str(settings.storage_dir / "queue.sqlite3"),
    immediate=os.getenv("LULU_QUEUE_IMMEDIATE", "0") == "1",
)


@huey.task()
def run_generation(job_id: str) -> None:
    if not repository.transition(job_id, "running", allowed_from=("queued",)):
        return
    job = repository.get(job_id)
    output = settings.storage_dir / "jobs" / job_id / "result.png"
    try:
        create_generator().generate(
            Path(job.original_path),
            Path(job.mask_path),
            Path(job.reference_path) if job.reference_path else None,
            output,
        )
        current = repository.get(job_id)
        if current.status == "cancelled":
            output.unlink(missing_ok=True)
            return
        repository.transition(job_id, "completed", result_path=str(output), allowed_from=("running",))
    except Exception as error:
        code = str(error) if str(error).isupper() else "GENERATION_FAILED"
        repository.transition(job_id, "failed", error_code=code[:64], retryable=True, allowed_from=("running",))
