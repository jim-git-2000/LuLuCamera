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
    catalog_path: Path = Path(__file__).with_name("characters.json")
    output_scale: int = 2
    inference_size: int = 1024
    steps: int = 30
    model_revision: str = "main"
    asset_dir: Path = Path(__file__).resolve().parents[2] / "assets/characters"
    generation_timeout_seconds: int = 300
    ip_adapter_id: str = "h94/IP-Adapter"
    ip_adapter_subfolder: str = "sdxl_models"
    ip_adapter_weight: str = "ip-adapter_sdxl.bin"

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            storage_dir=Path(os.getenv("LULU_STORAGE_DIR", "storage")).resolve(),
            max_upload_bytes=int(os.getenv("LULU_MAX_UPLOAD_BYTES", str(15 * 1024 * 1024))),
            max_active_jobs=int(os.getenv("LULU_MAX_ACTIVE_JOBS", "20")),
            ttl_hours=int(os.getenv("LULU_JOB_TTL_HOURS", "24")),
            generator=os.getenv("LULU_GENERATOR", "disabled"),
            model_id=os.getenv("LULU_MODEL_ID", "diffusers/stable-diffusion-xl-1.0-inpainting-0.1"),
            device=os.getenv("LULU_DEVICE", "cuda"),
            catalog_path=Path(os.getenv("LULU_CHARACTER_CATALOG", str(Path(__file__).with_name("characters.json")))),
            output_scale=int(os.getenv("LULU_OUTPUT_SCALE", "2")),
            inference_size=int(os.getenv("LULU_INFERENCE_SIZE", "1024")),
            steps=int(os.getenv("LULU_INFERENCE_STEPS", "30")),
            model_revision=os.getenv("LULU_MODEL_REVISION", "main"),
            asset_dir=Path(os.getenv("LULU_ASSET_DIR", str(Path(__file__).resolve().parents[2] / "assets/characters"))),
            generation_timeout_seconds=int(os.getenv("LULU_GENERATION_TIMEOUT_SECONDS", "300")),
            ip_adapter_id=os.getenv("LULU_IP_ADAPTER_ID", "h94/IP-Adapter"),
            ip_adapter_subfolder=os.getenv("LULU_IP_ADAPTER_SUBFOLDER", "sdxl_models"),
            ip_adapter_weight=os.getenv("LULU_IP_ADAPTER_WEIGHT", "ip-adapter_sdxl.bin"),
        )


settings = Settings.from_env()
settings.storage_dir.mkdir(parents=True, exist_ok=True)
