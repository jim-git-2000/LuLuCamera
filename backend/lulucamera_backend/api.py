from __future__ import annotations

import shutil
import time
import uuid
from collections import defaultdict, deque
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse

from .config import settings
from .repository import owner_hash
from .tasks import repository, run_generation, expire_files
from .editing import GenerationError, read_image, read_edits

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


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "generator": settings.generator}


@app.get("/capabilities")
def capabilities() -> dict:
    return {
        "mode": settings.generator,
        "available": settings.generator == "fake" or (settings.generator == "diffusers" and bool(settings.model_id)),
        "reference_required": False,
        "output_scale": settings.output_scale,
        "max_output_edge": 2048,
        "retention_hours": settings.ttl_hours,
    }


@app.post("/generations", status_code=202)
async def create_generation(
    original: UploadFile = File(...),
    mask: UploadFile = File(...),
    metadata: UploadFile = File(...),
    reference: UploadFile | None = File(None),
    instances: UploadFile | None = File(None),
    idempotency_key: str = Form(...),
    x_session_token: str | None = Header(None),
) -> dict:
    owner = authenticate(x_session_token)
    expire_files()
    if not capabilities()["available"]:
        raise HTTPException(503, detail={"code": "SERVICE_NOT_CONFIGURED", "retryable": False})
    if not 8 <= len(idempotency_key) <= 128:
        raise HTTPException(status_code=400, detail={"code": "INVALID_IDEMPOTENCY_KEY", "retryable": False})
    existing = repository.get_by_idempotency(owner, idempotency_key)
    if existing is not None:
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
        if paths["metadata"].stat().st_size > 65536:
            raise GenerationError("INVALID_METADATA")
        base = read_image(paths["original"])
        edit_mask = read_image(paths["mask"], "L")
        instance_image = None
        if instances is not None:
            await save_upload(instances, directory / "instances.png")
            instance_image = read_image(directory / "instances.png", "L")
        read_edits(paths["metadata"], edit_mask, base.size, instance_image)
        reference_path = None
        if reference is not None:
            await save_upload(reference, paths["reference"])
            if read_image(paths["reference"]).size != base.size:
                raise GenerationError("REFERENCE_SIZE_MISMATCH")
            reference_path = str(paths["reference"])
        job, created = repository.create(
            job_id=job_id,
            owner=owner,
            idempotency_key=idempotency_key,
            original_path=str(paths["original"]),
            mask_path=str(paths["mask"]),
            metadata_path=str(paths["metadata"]),
            reference_path=reference_path,
            max_active_jobs=settings.max_active_jobs,
        )
        persisted = True
        if created:
            run_generation(job.id)
            job = repository.get(job.id)
        else:
            shutil.rmtree(directory, ignore_errors=True)
        return job.public_dict()
    except GenerationError as error:
        if not persisted:
            shutil.rmtree(directory, ignore_errors=True)
        raise HTTPException(503 if error.retryable else 400,
                            detail={"code": error.code, "retryable": error.retryable}) from error
    except Exception:
        if not persisted:
            shutil.rmtree(directory, ignore_errors=True)
        raise


@app.get("/generations/{job_id}")
def get_generation(job_id: str, x_session_token: str | None = Header(None)) -> dict:
    owner = authenticate(x_session_token)
    expire_files()
    try:
        return repository.get_owned(job_id, owner).public_dict()
    except KeyError:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "retryable": False})


@app.get("/generations/{job_id}/result")
def get_result(job_id: str, x_session_token: str | None = Header(None)) -> FileResponse:
    owner = authenticate(x_session_token)
    expire_files()
    try:
        job = repository.get_owned(job_id, owner)
    except KeyError:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "retryable": False})
    if job.status == "expired":
        raise HTTPException(410, detail={"code": "RESULT_EXPIRED", "retryable": False})
    if job.status != "completed" or not job.result_path or not Path(job.result_path).is_file():
        raise HTTPException(status_code=409, detail={"code": "RESULT_NOT_READY", "retryable": True})
    return FileResponse(job.result_path, media_type="image/png", filename=f"{job.id}.png")


@app.delete("/generations/{job_id}")
def cancel_generation(job_id: str, x_session_token: str | None = Header(None)) -> dict:
    owner = authenticate(x_session_token)
    expire_files()
    try:
        job = repository.get_owned(job_id, owner)
    except KeyError:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "retryable": False})
    previous_status = repository.cancel(job_id)
    if previous_status not in ("running", "cancelled"):
        shutil.rmtree(Path(job.original_path).parent, ignore_errors=True)
    return repository.get(job_id).public_dict()


@app.post("/generations/{job_id}/retry")
def retry_generation(job_id: str, x_session_token: str | None = Header(None)) -> dict:
    owner = authenticate(x_session_token)
    expire_files()
    try:
        job = repository.get_owned(job_id, owner)
    except KeyError:
        raise HTTPException(404, detail={"code": "JOB_NOT_FOUND", "retryable": False})
    try:
        if repository.retry(job.id, settings.max_active_jobs):
            run_generation(job.id)
    except GenerationError as error:
        raise HTTPException(503, detail={"code": error.code, "retryable": error.retryable}) from error
    return repository.get(job.id).public_dict()


@app.delete("/generation-requests/{idempotency_key}")
def cancel_request(idempotency_key: str, x_session_token: str | None = Header(None)) -> dict:
    owner = authenticate(x_session_token)
    if not 8 <= len(idempotency_key) <= 128:
        raise HTTPException(400, detail={"code": "INVALID_IDEMPOTENCY_KEY", "retryable": False})
    job, previous = repository.cancel_by_key(owner, idempotency_key)
    if job and previous not in ("running", "cancelled"):
        shutil.rmtree(Path(job.original_path).parent, ignore_errors=True)
    return {"status": "cancelled"}
