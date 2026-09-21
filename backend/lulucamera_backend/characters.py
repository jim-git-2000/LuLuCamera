"""固定角色 ID 和可为空的外观；只从服务端配置加载提示词和参考图。"""
from dataclasses import dataclass
import json
from pathlib import Path

from .editing import GenerationError


@dataclass(frozen=True)
class Character:
    id: str
    prompt: str
    reference: Path | None


def load_characters(path: Path, asset_dir: Path | None = None) -> dict[str, Character]:
    try:
        payload = json.loads(path.read_text())
        items = payload["characters"]
        if not isinstance(items, list) or len(items) != 2:
            raise ValueError()
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise GenerationError("INVALID_CHARACTER_CATALOG") from exc
    characters = {}
    for item in items:
        if (not isinstance(item, dict) or item.get("id") not in ("LULU_A", "LULU_B") or
                not isinstance(item.get("prompt") or "", str) or
                not isinstance(item.get("referenceImage") or "", str)):
            raise GenerationError("INVALID_CHARACTER_CATALOG")
        reference = item.get("referenceImage")
        resolved = (path.parent / reference).resolve() if reference else None
        if resolved is None and asset_dir is not None:
            name = "lulu_a" if item["id"] == "LULU_A" else "lulu_b"
            resolved = next((p for p in (asset_dir / f"{name}_reference.png", asset_dir / f"{name}.png") if p.is_file()), None)
        if resolved is not None and not resolved.is_file():
            raise GenerationError("REFERENCE_IMAGE_MISSING")
        characters[item["id"]] = Character(
            item["id"],
            item.get("prompt") or "a friendly anthropomorphic capybara, full body, natural lighting",
            resolved,
        )
    if set(characters) != {"LULU_A", "LULU_B"}:
        raise GenerationError("INVALID_CHARACTER_CATALOG")
    return characters
