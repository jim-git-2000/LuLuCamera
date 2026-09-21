import importlib.util
from pathlib import Path
import pytest
from PIL import Image
from lulucamera_backend.characters import load_characters

spec = importlib.util.spec_from_file_location("asset_check", Path(__file__).parents[2] / "scripts/check-assets.py")
asset_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asset_check)


def test_missing_assets_use_placeholders(tmp_path):
    assert asset_check.check(tmp_path)["lulu_a.png"] == "placeholder"
    with pytest.raises(ValueError, match="缺少"):
        asset_check.check(tmp_path, require_all=True)


def test_opaque_sprite_is_rejected(tmp_path):
    Image.new("RGB", (64, 64), "red").save(tmp_path / "lulu_a.png")
    with pytest.raises(ValueError, match="透明背景"):
        asset_check.check(tmp_path)


def test_reference_defaults_to_sprite_but_explicit_reference_wins(tmp_path):
    sprite = Image.new("RGBA", (64, 64))
    sprite.putpixel((32, 32), (255, 0, 0, 255))
    sprite.save(tmp_path / "lulu_a.png")
    assert asset_check.check(tmp_path)["lulu_a.png"]["width"] == 64
    catalog = Path(__file__).parents[1] / "lulucamera_backend/characters.json"
    assert load_characters(catalog, tmp_path)["LULU_A"].reference == tmp_path / "lulu_a.png"
    Image.new("RGB", (64, 64), "white").save(tmp_path / "lulu_a_reference.png")
    assert load_characters(catalog, tmp_path)["LULU_A"].reference == tmp_path / "lulu_a_reference.png"
