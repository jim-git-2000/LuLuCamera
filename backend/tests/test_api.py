import hashlib
from dataclasses import replace
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from lulucamera_backend import api, generators, tasks
from lulucamera_backend.repository import JobRepository
from test_editing import scene

TOKEN = {"X-Session-Token": "a" * 32}


@pytest.fixture
def service(tmp_path, monkeypatch):
    config = replace(api.settings, storage_dir=tmp_path / "service", generator="fake")
    repo = JobRepository(config.storage_dir / "jobs.sqlite3")
    for module in (api, tasks, generators):
        monkeypatch.setattr(module, "settings", config)
    monkeypatch.setattr(api, "repository", repo)
    monkeypatch.setattr(tasks, "repository", repo)
    monkeypatch.setattr(api, "run_generation", lambda job_id: tasks.run_generation.call_local(job_id))
    monkeypatch.setattr(tasks, "create_generator", lambda: generators.FakeGenerator())
    api.requests_by_owner.clear()
    scene(tmp_path)
    with TestClient(api.app) as client:
        yield client, repo, tmp_path


def create(service, key="capture-123"):
    client, _, directory = service
    return client.post("/generations", headers=TOKEN, data={"idempotency_key": key}, files={
        "original": ("original.png", (directory / "original.png").read_bytes(), "image/png"),
        "mask": ("mask.png", (directory / "mask.png").read_bytes(), "image/png"),
        "metadata": ("metadata.json", (directory / "metadata.json").read_bytes(), "application/json"),
    })


def test_create_download_idempotency_and_owner_isolation(service):
    client, repo, _ = service
    response = create(service)
    assert response.status_code == 202
    data = response.json()
    assert data["status"] == "completed" and data["mode"] == "fake"
    assert (data["result_width"], data["result_height"]) == (256, 128)
    assert "original_path" not in data and "owner_hash" not in data
    assert create(service).json()["id"] == data["id"]
    result = client.get(data["result_url"], headers=TOKEN)
    assert result.status_code == 200
    assert len(result.content) == data["result_bytes"]
    assert hashlib.sha256(result.content).hexdigest() == data["result_sha256"]
    other = {"X-Session-Token": "b" * 32}
    for path in (f'/generations/{data["id"]}', data["result_url"]):
        assert client.get(path, headers=other).status_code == 404
    assert client.delete(f'/generations/{data["id"]}', headers=other).status_code == 404
    assert client.post(f'/generations/{data["id"]}/retry', headers=other).status_code == 404
    assert client.get(data["result_url"]).status_code == 401
    assert client.delete(f'/generations/{data["id"]}', headers=TOKEN).json()["status"] == "cancelled"
    assert not Path(repo.get(data["id"]).original_path).exists()
    assert client.get(data["result_url"], headers=TOKEN).status_code == 409


def test_failed_create_is_not_implicit_retry(service, monkeypatch):
    client, repo, _ = service
    monkeypatch.setattr(api, "run_generation", lambda job_id: None)
    data = create(service).json()
    assert repo.transition(data["id"], "failed", error_code="WORKER_INTERRUPTED", retryable=True)
    assert create(service).json()["status"] == "failed"
    assert client.post(f'/generations/{data["id"]}/retry', headers=TOKEN).json()["status"] == "queued"
    assert repo.get(data["id"]).id == data["id"]


def test_expiry_and_disabled_service(service, monkeypatch):
    client, repo, _ = service
    response = create(service).json()
    with repo._connect() as connection:
        connection.execute("UPDATE generation_jobs SET expires_at='2000-01-01T00:00:00+00:00'")
    assert client.get(response["result_url"], headers=TOKEN).status_code == 410
    assert not Path(repo.get(response["id"]).original_path).exists()
    monkeypatch.setattr(api, "settings", replace(api.settings, generator="disabled"))
    assert client.get("/capabilities").json()["available"] is False
    assert create(service, "capture-456").status_code == 503


def test_cancel_during_worker_does_not_publish(service, monkeypatch):
    client, repo, _ = service
    monkeypatch.setattr(api, "run_generation", lambda job_id: None)
    data = create(service).json()
    class CancellingGenerator:
        def generate(self, original, mask, reference, output, metadata, cancelled):
            repo.cancel(data["id"])
            assert cancelled()
            from PIL import Image
            Image.new("RGB", (128, 64)).save(output)
    monkeypatch.setattr(tasks, "create_generator", lambda: CancellingGenerator())
    tasks.run_generation.call_local(data["id"])
    assert repo.get(data["id"]).status == "cancelled"
    assert not Path(repo.get(data["id"]).original_path).parent.exists()


def test_cancel_before_upload_blocks_late_create_without_photos(service):
    client, repo, _ = service
    response = client.delete("/generation-requests/capture-123", headers=TOKEN)
    assert response.status_code == 200
    assert response.json()["status"] == "cancelled"
    response = create(service)
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "REQUEST_CANCELLED"
    assert repo.active_count() == 0
    assert not list((repo.database.parent / "jobs").glob("*/original.bin"))
