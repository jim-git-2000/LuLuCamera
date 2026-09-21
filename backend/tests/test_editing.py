import json
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from lulucamera_backend.characters import load_characters
from lulucamera_backend.editing import GenerationError, read_edits


def scene(tmp_path: Path):
    original = Image.new("RGB", (128, 64), (10, 20, 30))
    mask = Image.new("L", original.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rectangle((15, 18, 28, 40), fill=255)
    draw.rectangle((96, 18, 110, 40), fill=255)
    metadata = {"schemaVersion": 1, "width": 128, "height": 64, "people": [
        {"trackId": 1, "character": "LULU_A", "state": "TRACKED", "bounds": [.08, .2, .26, .8]},
        {"trackId": 2, "character": "HUMAN", "state": "TRACKED", "bounds": [.42, .2, .58, .8]},
        {"trackId": 3, "character": "LULU_B", "state": "TRACKED", "bounds": [.72, .2, .9, .8]},
    ]}
    original.save(tmp_path / "original.png")
    mask.save(tmp_path / "mask.png")
    (tmp_path / "metadata.json").write_text(json.dumps(metadata))
    return original, mask, metadata


def test_per_person_masks_keep_binding_and_unselected_person(tmp_path):
    _, mask, _ = scene(tmp_path)
    edits = read_edits(tmp_path / "metadata.json", mask, mask.size)
    assert [(e.track_id, e.character) for e in edits] == [(1, "LULU_A"), (3, "LULU_B")]
    assert edits[0].mask.getpixel((20, 24)) == 255
    assert edits[0].mask.getpixel((100, 24)) == 0
    assert all(e.mask.getpixel((64, 24)) == 0 for e in edits)


@pytest.mark.parametrize("mutation,code", [
    ("outside", "MASK_OUTSIDE_EDIT_REGION"), ("overlap", "PEOPLE_OVERLAP"),
    ("empty", "EMPTY_EDIT_MASK"), ("duplicate", "INVALID_METADATA"),
    ("size", "MASK_SIZE_MISMATCH"), ("nan", "INVALID_METADATA"),
])
def test_invalid_inputs(tmp_path, mutation, code):
    _, mask, metadata = scene(tmp_path)
    if mutation == "outside": mask.putpixel((0, 0), 255)
    if mutation == "overlap": metadata["people"][1]["bounds"] = [.1, .2, .3, .8]
    if mutation == "empty": mask.paste(0, (0, 0, *mask.size))
    if mutation == "duplicate": metadata["people"][1]["trackId"] = 1
    if mutation == "size": mask = mask.resize((64, 32))
    if mutation == "nan": metadata["people"][0]["bounds"][0] = float("nan")
    (tmp_path / "metadata.json").write_text(json.dumps(metadata))
    with pytest.raises(GenerationError, match=code):
        read_edits(tmp_path / "metadata.json", mask, (128, 64))


def test_blank_character_assets_are_valid():
    catalog = load_characters(Path(__file__).parents[1] / "lulucamera_backend/characters.json")
    assert set(catalog) == {"LULU_A", "LULU_B"}
    assert all(c.reference is None and "capybara" in c.prompt for c in catalog.values())


def test_instance_mask_protects_limb_outside_other_person_box(tmp_path):
    _, mask, metadata = scene(tmp_path)
    for person in metadata["people"]:
        person["maskLabel"] = person["trackId"]
    (tmp_path / "metadata.json").write_text(json.dumps(metadata))
    instances = Image.new("L", mask.size)
    instances.putpixel((20, 24), 1)
    instances.putpixel((100, 24), 3)
    instances.putpixel((21, 24), 2)  # 旁人手臂伸出其框，落入 A 的编辑区。
    instances.putpixel((22, 24), 255)  # 重叠置信度不能区分的像素。
    edits = read_edits(tmp_path / "metadata.json", mask, mask.size, instances)
    assert edits[0].mask.getpixel((20, 24)) == 255
    assert edits[0].mask.getpixel((21, 24)) == 0
    assert edits[0].mask.getpixel((22, 24)) == 0
