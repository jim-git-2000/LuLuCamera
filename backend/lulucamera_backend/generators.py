from __future__ import annotations

from functools import lru_cache
import hashlib
import json
from pathlib import Path
from typing import Callable, Protocol

from PIL import Image, ImageOps

from .characters import load_characters
from .config import settings
from .editing import GenerationError, read_edits, read_image


class Generator(Protocol):
    def generate(self, original: Path, mask: Path, reference: Path | None, output: Path,
                 metadata: Path, cancelled: Callable[[], bool]) -> None: ...


def protected_composite(original: Image.Image, generated: Image.Image, mask: Image.Image) -> Image.Image:
    if original.size != generated.size or original.size != mask.size:
        raise GenerationError("RESULT_SIZE_MISMATCH")
    return Image.composite(generated.convert("RGB"), original.convert("RGB"), mask.convert("L"))


def prepare(original: Path, mask: Path, metadata: Path):
    base = read_image(original)
    edit_mask = read_image(mask, "L")
    instance_path = metadata.parent / "instances.png"
    instances = read_image(instance_path, "L") if instance_path.is_file() else None
    edits = read_edits(metadata, edit_mask, base.size, instances)
    if settings.output_scale not in (1, 2) or settings.inference_size not in (512, 768, 1024):
        raise GenerationError("INVALID_GENERATION_CONFIG")
    if not 1 <= settings.steps <= 60:
        raise GenerationError("INVALID_GENERATION_CONFIG")
    scale = min(settings.output_scale, 2048 / max(base.size))
    size = tuple(max(8, round(n * scale)) for n in base.size)
    base = base.resize(size, Image.Resampling.LANCZOS)
    for edit in edits:
        edit.mask = edit.mask.resize(size, Image.Resampling.NEAREST)
    return base, edits


class FakeGenerator:
    """流程演示：使用即时合成图，不宣称模型生成或细节恢复。"""
    def generate(self, original: Path, mask: Path, reference: Path | None, output: Path,
                 metadata: Path, cancelled: Callable[[], bool] = lambda: False) -> None:
        base, edits = prepare(original, mask, metadata)
        candidate = read_image(reference).resize(base.size, Image.Resampling.LANCZOS) if reference else base
        for edit in edits:
            if cancelled():
                raise GenerationError("CANCELLED")
            base = protected_composite(base, candidate, edit.mask)
        base.save(output, format="PNG")


class DiffusersGenerator:
    """复用 SD/SDXL Inpainting，逐人生成后强制回贴；模型由 Worker 缓存。"""
    def __init__(self) -> None:
        if not settings.model_id:
            raise GenerationError("MODEL_NOT_CONFIGURED")
        import torch
        from diffusers import AutoPipelineForInpainting

        self.torch = torch
        dtype = torch.float16 if settings.device.startswith("cuda") else torch.float32
        self.pipeline = AutoPipelineForInpainting.from_pretrained(
            settings.model_id, revision=settings.model_revision, torch_dtype=dtype,
        ).to(settings.device)
        self.adapter_loaded = False

    def ensure_adapter(self) -> None:
        if not self.adapter_loaded:
            if not settings.ip_adapter_id:
                raise GenerationError("REFERENCE_ADAPTER_NOT_CONFIGURED")
            self.pipeline.load_ip_adapter(
                settings.ip_adapter_id, subfolder=settings.ip_adapter_subfolder,
                weight_name=settings.ip_adapter_weight,
            )
            self.adapter_loaded = True

    def generate(self, original: Path, mask: Path, reference: Path | None, output: Path,
                 metadata: Path, cancelled: Callable[[], bool] = lambda: False) -> None:
        base, edits = prepare(original, mask, metadata)
        catalog = load_characters(settings.catalog_path, settings.asset_dir)
        if any(catalog[edit.character].reference for edit in edits):
            self.ensure_adapter()
        seed = int(hashlib.sha256(metadata.read_bytes()).hexdigest()[:8], 16)
        diagnostics = []
        for edit in edits:
            if cancelled():
                raise GenerationError("CANCELLED")
            character = catalog[edit.character]
            # 裁剪上下文后等比例缩放和补边，避免把竖幅照片压成正方形。
            x0, y0, x1, y1 = edit.mask.getbbox()
            padding = max(32, round(max(x1-x0, y1-y0) * .2))
            box = (max(0, x0-padding), max(0, y0-padding), min(base.width, x1+padding), min(base.height, y1+padding))
            crop = base.crop(box)
            edge = settings.inference_size
            fitted = ImageOps.contain(crop, (edge, edge), Image.Resampling.LANCZOS)
            offset = ((edge-fitted.width)//2, (edge-fitted.height)//2)
            canvas = Image.new("RGB", (edge, edge), (127, 127, 127))
            canvas.paste(fitted, offset)
            canvas_mask = Image.new("L", (edge, edge), 0)
            canvas_mask.paste(edit.mask.crop(box).resize(fitted.size, Image.Resampling.NEAREST), offset)
            extra = {}
            if getattr(self, "adapter_loaded", False):
                # 未提供外观参考时权重为零；不能把即时画面冒充角色参考图。
                if character.reference:
                    rgba = read_image(character.reference, "RGBA")
                    ref_image = Image.new("RGB", rgba.size, "white")
                    ref_image.paste(rgba, mask=rgba.getchannel("A"))
                else:
                    ref_image = Image.new("RGB", (224, 224))
                self.pipeline.set_ip_adapter_scale(.6 if character.reference else 0.0)
                extra["ip_adapter_image"] = ref_image

            def on_step(pipeline, step, timestep, callback_kwargs):
                if cancelled():
                    raise GenerationError("CANCELLED")
                return callback_kwargs

            generator = self.torch.Generator(device=settings.device).manual_seed(seed)
            result = self.pipeline(
                prompt=character.prompt + ", keep the subject's pose and scene lighting",
                image=canvas, mask_image=canvas_mask, width=edge, height=edge,
                num_inference_steps=settings.steps, generator=generator,
                callback_on_step_end=on_step, **extra,
            )
            if any(getattr(result, "nsfw_content_detected", None) or []):
                raise GenerationError("OUTPUT_REJECTED")
            generated = result.images[0]
            if generated.size != (edge, edge):
                raise GenerationError("RESULT_SIZE_MISMATCH")
            decoded = generated.crop((offset[0], offset[1], offset[0]+fitted.width, offset[1]+fitted.height))
            candidate = base.copy()
            candidate.paste(decoded.resize(crop.size, Image.Resampling.LANCZOS), box[:2])
            base = protected_composite(base, candidate, edit.mask)
            diagnostics.append({"trackId": edit.track_id, "character": edit.character, "seed": seed})
        base.save(output, format="PNG")
        output.with_suffix(".json").write_text(json.dumps({
            "mode": "diffusers", "model": settings.model_id, "revision": settings.model_revision,
            "width": base.width, "height": base.height, "steps": settings.steps, "people": diagnostics,
        }))


@lru_cache(maxsize=1)
def create_generator() -> Generator:
    if settings.generator == "fake":
        return FakeGenerator()
    if settings.generator == "diffusers":
        return DiffusersGenerator()
    raise GenerationError("SERVICE_NOT_CONFIGURED")
