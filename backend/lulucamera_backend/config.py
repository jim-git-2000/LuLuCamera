from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    storage_dir: Path
    max_upload_bytes: int
    max_active_jobs: int
    ttl_hours: int
    generator: str
    model_id: str
    device: str

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            storage_dir=Path(os.getenv("LULU_STORAGE_DIR", "storage")).resolve(),
            max_upload_bytes=int(os.getenv("LULU_MAX_UPLOAD_BYTES", str(15 * 1024 * 1024))),
            max_active_jobs=int(os.getenv("LULU_MAX_ACTIVE_JOBS", "20")),
            ttl_hours=int(os.getenv("LULU_JOB_TTL_HOURS", "24")),
            generator=os.getenv("LULU_GENERATOR", "fake"),
            model_id=os.getenv("LULU_MODEL_ID", ""),
            device=os.getenv("LULU_DEVICE", "cuda"),
        )


settings = Settings.from_env()
settings.storage_dir.mkdir(parents=True, exist_ok=True)
