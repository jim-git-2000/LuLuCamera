from pathlib import Path

from lulucamera_backend.repository import JobRepository, owner_hash


def test_idempotency_is_scoped_to_owner(tmp_path: Path) -> None:
    repository = JobRepository(tmp_path / "jobs.sqlite3")
    common = dict(
        idempotency_key="capture-123",
        original_path="original.png",
        mask_path="mask.png",
        metadata_path="metadata.json",
        reference_path=None,
    )
    first, created = repository.create(job_id="one", owner=owner_hash("a" * 32), **common)
    repeated, repeated_created = repository.create(job_id="two", owner=owner_hash("a" * 32), **common)
    other, other_created = repository.create(job_id="three", owner=owner_hash("b" * 32), **common)
    assert created is True
    assert repeated_created is False
    assert repeated.id == first.id
    assert other_created is True
    assert other.id != first.id


def test_cancelled_job_cannot_be_completed(tmp_path: Path) -> None:
    repository = JobRepository(tmp_path / "jobs.sqlite3")
    job, _ = repository.create(
        job_id="one",
        owner=owner_hash("a" * 32),
        idempotency_key="capture-123",
        original_path="original.png",
        mask_path="mask.png",
        metadata_path="metadata.json",
        reference_path=None,
    )
    assert repository.transition(job.id, "cancelled", allowed_from=("queued",))
    assert not repository.transition(job.id, "completed", result_path="result.png", allowed_from=("running",))


def test_owner_cannot_read_another_sessions_job(tmp_path: Path) -> None:
    repository = JobRepository(tmp_path / "jobs.sqlite3")
    job, _ = repository.create(
        job_id="one",
        owner=owner_hash("a" * 32),
        idempotency_key="capture-123",
        original_path="original.png",
        mask_path="mask.png",
        metadata_path="metadata.json",
        reference_path=None,
    )
    try:
        repository.get_owned(job.id, owner_hash("b" * 32))
    except KeyError:
        pass
    else:
        raise AssertionError("其他会话不应读取该任务")


def test_atomic_queue_capacity_and_explicit_retry(tmp_path):
    import pytest
    from lulucamera_backend.editing import GenerationError
    repo = JobRepository(tmp_path / "jobs.sqlite3")
    args = dict(owner="owner", original_path="original.png", mask_path="mask.png",
                metadata_path="metadata.json", reference_path=None, max_active_jobs=1)
    first, _ = repo.create(job_id="one", idempotency_key="capture-one", **args)
    with pytest.raises(GenerationError, match="QUEUE_FULL"):
        repo.create(job_id="two", idempotency_key="capture-two", **args)
    assert repo.transition(first.id, "failed", retryable=True)
    repo.create(job_id="two", idempotency_key="capture-two", **args)
    with pytest.raises(GenerationError, match="QUEUE_FULL"):
        repo.retry(first.id, 1)
    assert repo.cancel("two") == "queued"
    assert repo.retry(first.id, 1)
    assert not repo.retry(first.id, 1)


def test_interrupted_worker_recovery(tmp_path):
    repo = JobRepository(tmp_path / "jobs.sqlite3")
    repo.create(job_id="one", owner="owner", idempotency_key="capture-one", original_path="original.png",
                mask_path="mask.png", metadata_path="metadata.json", reference_path=None)
    assert repo.transition("one", "running", allowed_from=("queued",))
    assert repo.recover_interrupted() == 1
    assert repo.get("one").error_code == "WORKER_INTERRUPTED"
    assert repo.get("one").retryable
