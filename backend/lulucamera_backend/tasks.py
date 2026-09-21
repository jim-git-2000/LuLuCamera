from __future__ import annotations

import hashlib
import json
import os
import shutil
from pathlib import Path
from datetime import UTC, datetime

from huey import SqliteHuey, crontab
from PIL import Image

from .config import settings
from .editing import GenerationError
from .generators import create_generator
from .repository import JobRepository

repository = JobRepository(settings.storage_dir / "jobs.sqlite3", settings.ttl_hours)
huey = SqliteHuey("lulucamera", filename=str(settings.storage_dir / "queue.sqlite3"),
                 immediate=os.getenv("LULU_QUEUE_IMMEDIATE", "0") == "1")


def expire_files() -> None:
    for job in repository.expire_due():
        shutil.rmtree(Path(job.original_path).parent, ignore_errors=True)


@huey.task()
def run_generation(job_id: str) -> None:
    if not repository.transition(job_id, "running", allowed_from=("queued",)):
        return
    job = repository.get(job_id)
    directory = Path(job.original_path).parent
    output = directory / "result.png"
    temporary = directory / "result.part.png"

    def cancelled() -> bool:
        return (repository.get(job_id).status != "running" or
                datetime.fromisoformat(job.expires_at) <= datetime.now(UTC))

    try:
        generate = create_generator().generate if settings.generator != "diffusers" else None
        if settings.generator == "diffusers":
            from .runtime import runner
            generate = lambda *args: runner.generate(*args, timeout=settings.generation_timeout_seconds)
        generate(
            Path(job.original_path), Path(job.mask_path),
            Path(job.reference_path) if job.reference_path else None,
            temporary, Path(job.metadata_path), cancelled,
        )
        if cancelled():
            temporary.unlink(missing_ok=True)
            expire_files()
            return
        temporary.replace(output)
        with Image.open(output) as image:
            receipt = {"mode": settings.generator, "result_width": image.width,
                       "result_height": image.height, "result_bytes": output.stat().st_size,
                       "result_sha256": hashlib.sha256(output.read_bytes()).hexdigest()}
        (directory / "receipt.part.json").write_text(json.dumps(receipt))
        (directory / "receipt.part.json").replace(directory / "receipt.json")
        if not repository.transition(job_id, "completed", result_path=str(output), allowed_from=("running",)):
            output.unlink(missing_ok=True)
    except GenerationError as error:
        expire_files()
        repository.transition(job_id, "failed", error_code=error.code, retryable=error.retryable, allowed_from=("running",))
    except Exception:
        # 不向客户端透传模型错误文本或服务器路径。
        repository.transition(job_id, "failed", error_code="GENERATION_FAILED", retryable=True, allowed_from=("running",))
    finally:
        temporary.unlink(missing_ok=True)
        if repository.get(job_id).status in ("cancelled", "expired"):
            shutil.rmtree(directory, ignore_errors=True)


@huey.on_startup()
def recover_worker() -> None:
    # 仅单 Consumer / 单 Worker 模式支持恢复；API 重启不得改变 GPU 任务状态。
    repository.recover_interrupted()
    maintain_jobs()


@huey.periodic_task(crontab(minute="*"))
def maintain_jobs() -> None:
    expire_files()
    for job_id in repository.queued_ids():
        # 弥补 SQLite 提交后、Huey enqueue 前进程退出的窗口；任务用 CAS 抢占。
        run_generation(job_id)
