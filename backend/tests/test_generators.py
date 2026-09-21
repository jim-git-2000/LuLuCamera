from PIL import Image

from lulucamera_backend.generators import protected_composite


def test_pixels_outside_mask_are_unchanged() -> None:
    original = Image.new("RGB", (4, 4), (10, 20, 30))
    generated = Image.new("RGB", (4, 4), (220, 210, 200))
    mask = Image.new("L", (4, 4), 0)
    mask.putpixel((1, 1), 255)
    result = protected_composite(original, generated, mask)
    for y in range(4):
        for x in range(4):
            expected = (220, 210, 200) if (x, y) == (1, 1) else (10, 20, 30)
            assert result.getpixel((x, y)) == expected


def test_sequential_generation_and_scaled_background(tmp_path, monkeypatch):
    import json
    from dataclasses import replace
    from types import SimpleNamespace
    from lulucamera_backend import generators
    from test_editing import scene

    original, mask, _ = scene(tmp_path)
    catalog = tmp_path / "characters.json"
    catalog.write_text(json.dumps({"characters": [
        {"id": "LULU_A", "prompt": "character A"}, {"id": "LULU_B", "prompt": "character B"},
    ]}))
    monkeypatch.setattr(generators, "settings", replace(generators.settings,
        catalog_path=catalog, asset_dir=tmp_path / "empty-assets", output_scale=2, inference_size=512))
    calls = []

    def pipeline(**kwargs):
        calls.append(kwargs)
        kwargs["callback_on_step_end"](None, 0, 0, {})
        color = (200, 0, 0) if "character A" in kwargs["prompt"] else (0, 200, 0)
        return SimpleNamespace(images=[Image.new("RGB", (512, 512), color)])

    generator = generators.DiffusersGenerator.__new__(generators.DiffusersGenerator)
    generator.pipeline = pipeline
    generator.torch = SimpleNamespace(Generator=lambda **kw: SimpleNamespace(manual_seed=lambda seed: seed))
    output = tmp_path / "result.png"
    generator.generate(tmp_path / "original.png", tmp_path / "mask.png", None, output, tmp_path / "metadata.json")
    assert len(calls) == 2
    assert "character A" in calls[0]["prompt"] and "character B" in calls[1]["prompt"]
    result = Image.open(output)
    assert result.size == (256, 128)
    assert result.getpixel((40, 48)) == (200, 0, 0)
    assert result.getpixel((200, 48)) == (0, 200, 0)
    scaled = original.resize(result.size, Image.Resampling.LANCZOS)
    scaled_mask = mask.resize(result.size, Image.Resampling.NEAREST)
    for y in range(result.height):
        for x in range(result.width):
            if scaled_mask.getpixel((x, y)) == 0:
                assert result.getpixel((x, y)) == scaled.getpixel((x, y))


def test_cancelled_generation_does_not_publish(tmp_path):
    import pytest
    from lulucamera_backend.generators import FakeGenerator
    from lulucamera_backend.editing import GenerationError
    from test_editing import scene
    scene(tmp_path)
    output = tmp_path / "result.png"
    with pytest.raises(GenerationError, match="CANCELLED"):
        FakeGenerator().generate(tmp_path / "original.png", tmp_path / "mask.png", None,
                                 output, tmp_path / "metadata.json", lambda: True)
    assert not output.exists()
