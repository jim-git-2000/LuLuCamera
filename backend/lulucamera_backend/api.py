from __future__ import annotations

import json
import shutil
import time
import uuid
from collections import defaultdict, deque
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse

from .config import settings
from .repository import ACTIVE_STATUSES, owner_hash
from .tasks import repository, run_generation

app = FastAPI(title="LuLuCamera Generation API", version="1.0.0")
requests_by_owner: dict[str, deque[float]] = defaultdict(deque)


def authenticate(token: str | None) -> str:
    if token is None or len(token) < 32 or len(token) > 256:
        raise HTTPException(status_code=401, detail={"code": "INVALID_SESSION_TOKEN", "retryable": False})
    owner = owner_hash(token)
    now = time.monotonic()
    window = requests_by_owner[owner]
    while window and window[0] < now - 60:
        window.popleft()
    if len(window) >= 60:
        raise HTTPException(status_code=429, detail={"code": "RATE_LIMITED", "retryable": True})
    window.append(now)
    return owner


async def save_upload(upload: UploadFile, target: Path) -> None:
    written = 0
    with target.open("wb") as output:
        while chunk := await upload.read(1024 * 1024):
            written += len(chunk)
            if written > settings.max_upload_bytes:
                raise HTTPException(status_code=413, detail={"code": "UPLOAD_TOO_LARGE", "retryable": False})
            output.write(chunk)
    if written == 0:
        raise HTTPException(status_code=400, detail={"code": "EMPTY_UPLOAD", "retryable": False})


@app.on_event("startup")
def recover_interrupted_jobs() -> None:
    repository.recover_interrupted()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "generator": settings.generator}


@app.post("/generations", status_code=202)
async def create_generation(
    original: UploadFile = File(...),
    mask: UploadFile = File(...),
    metadata: UploadFile = File(...),
    reference: UploadFile | None = File(None),
    idempotency_key: str = Form(...),
    x_session_token: str | None = Header(None),
) -> dict:
    owner = authenticate(x_session_token)
    if not 8 <= len(idempotency_key) <= 128:
        raise HTTPException(status_code=400, detail={"code": "INVALID_IDEMPOTENCY_KEY", "retryable": False})
    existing = repository.get_by_idempotency(owner, idempotency_key)
    if existing is not None:
        if existing.status == "failed" and existing.retryable:
            repository.transition(existing.id, "queued", allowed_from=("failed",))
            run_generation(existing.id)
            existing = repository.get(existing.id)
        return existing.public_dict()
    if repository.active_count() >= settings.max_active_jobs:
        raise HTTPException(status_code=503, detail={"code": "QUEUE_FULL", "retryable": True})
    job_id = str(uuid.uuid4())
    directory = settings.storage_dir / "jobs" / job_id
    directory.mkdir(parents=True)
    persisted = False
    paths = {
        "original": directory / "original.bin",
        "mask": directory / "mask.bin",
        "metadata": directory / "metadata.json",
        "reference": directory / "reference.bin",
    }
    try:
        await save_upload(original, paths["original"])
        await save_upload(mask, paths["mask"])
        await save_upload(metadata, paths["metadata"])
        try:
            payload = json.loads(paths["metadata"].read_text())
            if payload.get("schemaVersion") != 1:
                raise ValueError
        except (json.JSONDecodeError, ValueError):
            raise HTTPException(status_code=400, detail={"code": "INVALID_METADATA", "retryable": False})
        reference_path = None
        if reference is not None:
            await save_upload(reference, paths["reference"])
            reference_path = str(paths["reference"])
        job, created = repository.create(
            job_id=job_id,
            owner=owner,
            idempotency_key=idempotency_key,
            original_path=str(paths["original"]),
            mask_path=str(paths["mask"]),
            metadata_path=str(paths["metadata"]),
            reference_path=reference_path,
        )
        persisted = True
        if created:
            run_generation(job.id)
            job = repository.get(job.id)
        else:
            shutil.rmtree(directory, ignore_errors=True)
            if job.status == "failed" and job.retryable:
                repository.transition(job.id, "queued", allowed_from=("failed",))
                run_generation(job.id)
                job = repository.get(job.id)
        return job.public_dict()
    except Exception:
        if not persisted:
            shutil.rmtree(directory, ignore_errors=True)
        raise


@app.get("/generations/{job_id}")
def get_generation(job_id: str, x_session_token: str | None = Header(None)) -> dict:
    owner = authenticate(x_session_token)
    try:
        return repository.get_owned(job_id, owner).public_dict()
    except KeyError:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "retryable": False})


@app.get("/generations/{job_id}/result")
def get_result(job_id: str, x_session_token: str | None = Header(None)) -> FileResponse:
    owner = authenticate(x_session_token)
    try:
        job = repository.get_owned(job_id, owner)
    except KeyError:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "retryable": False})
    if job.status != "completed" or not job.result_path or not Path(job.result_path).is_file():
        raise HTTPException(status_code=409, detail={"code": "RESULT_NOT_READY", "retryable": True})
    return FileResponse(job.result_path, media_type="image/png", filename=f"{job.id}.png")


@app.delete("/generations/{job_id}")
def cancel_generation(job_id: str, x_session_token: str | None = Header(None)) -> dict:
    owner = authenticate(x_session_token)
    try:
        job = repository.get_owned(job_id, owner)
    except KeyError:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "retryable": False})
    if job.status in ACTIVE_STATUSES:
        repository.transition(job_id, "cancelled", allowed_from=ACTIVE_STATUSES)
    elif job.status == "completed":
        if job.result_path:
            Path(job.result_path).unlink(missing_ok=True)
        repository.transition(job_id, "cancelled", allowed_from=("completed",))
    return repository.get(job_id).public_dict()
