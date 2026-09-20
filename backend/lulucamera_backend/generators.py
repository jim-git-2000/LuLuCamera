from __future__ import annotations

from pathlib import Path
from typing import Protocol

from PIL import Image, ImageEnhance

from .config import settings


class Generator(Protocol):
    def generate(self, original: Path, mask: Path, reference: Path | None, output: Path) -> None: ...


def protected_composite(original: Image.Image, generated: Image.Image, mask: Image.Image) -> Image.Image:
    base = original.convert("RGB")
    candidate = generated.convert("RGB").resize(base.size)
    edit_mask = mask.convert("L").resize(base.size)
    return Image.composite(candidate, base, edit_mask)


class FakeGenerator:
    """CI 使用的确定性适配器；只改变掩码内像素，不代表生成质量。"""

    def generate(self, original: Path, mask: Path, reference: Path | None, output: Path) -> None:
        with Image.open(original) as base, Image.open(mask) as edit_mask:
            if reference:
                with Image.open(reference) as candidate:
                    result = protected_composite(base, candidate, edit_mask)
            else:
                candidate = ImageEnhance.Color(base.convert("RGB")).enhance(0.35)
                result = protected_composite(base, candidate, edit_mask)
            result.save(output, format="PNG")


class DiffusersGenerator:
    """延迟加载 Diffusers inpainting；最终像素仍由掩码回贴约束。"""

    def __init__(self) -> None:
        if not settings.model_id:
            raise RuntimeError("LULU_MODEL_ID_REQUIRED")
        import torch
        from diffusers import AutoPipelineForInpainting

        dtype = torch.float16 if settings.device.startswith("cuda") else torch.float32
        self.pipeline = AutoPipelineForInpainting.from_pretrained(settings.model_id, torch_dtype=dtype)
        self.pipeline.to(settings.device)

    def generate(self, original: Path, mask: Path, reference: Path | None, output: Path) -> None:
        with Image.open(original) as base, Image.open(mask) as edit_mask:
            prompt = "a friendly stylized capybara character, preserve pose and lighting"
            generated = self.pipeline(
                prompt=prompt,
                image=base.convert("RGB"),
                mask_image=edit_mask.convert("L"),
                num_inference_steps=30,
            ).images[0]
            protected_composite(base, generated, edit_mask).save(output, format="PNG")


def create_generator() -> Generator:
    if settings.generator == "fake":
        return FakeGenerator()
    if settings.generator == "diffusers":
        return DiffusersGenerator()
    raise RuntimeError("UNKNOWN_GENERATOR")
