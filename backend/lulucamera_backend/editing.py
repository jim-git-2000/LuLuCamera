"""输入约束与逐人编辑区；框掩码阶段对相交人物保守拒绝。"""
from dataclasses import dataclass
import json
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, UnidentifiedImageError


class GenerationError(Exception):
    def __init__(self, code: str, retryable: bool = False):
        super().__init__(code)
        self.code = code
        self.retryable = retryable


@dataclass
class PersonEdit:
    track_id: int
    character: str
    mask: Image.Image


def read_image(path: Path, mode: str = "RGB") -> Image.Image:
    try:
        with Image.open(path) as image:
            if image.width * image.height > 16_777_216 or min(image.size) < 8:
                raise GenerationError("INVALID_IMAGE_SIZE")
            image.load()
            return image.convert(mode)
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
        raise GenerationError("INVALID_IMAGE") from exc


def read_edits(metadata: Path, mask: Image.Image, size: tuple[int, int], instances: Image.Image | None = None) -> list[PersonEdit]:
    try:
        payload = json.loads(metadata.read_text())
        if payload["schemaVersion"] != 1 or (payload["width"], payload["height"]) != size:
            raise ValueError()
        people = payload["people"]
        if not isinstance(people, list) or not 1 <= len(people) <= 3:
            raise ValueError()
        boxes = []
        ids = set()
        for person in people:
            track_id = person["trackId"]
            if type(track_id) is not int or track_id in ids:
                raise ValueError()
            ids.add(track_id)
            character = person["character"]
            if character not in ("HUMAN", "LULU_A", "LULU_B"):
                raise ValueError()
            left, top, right, bottom = map(float, person["bounds"])
            if not all(math.isfinite(n) for n in (left, top, right, bottom)):
                raise ValueError()
            if not (0 <= left < right <= 1 and 0 <= top < bottom <= 1):
                raise ValueError()
            selected = character != "HUMAN" and person.get("state") == "TRACKED"
            dx, dy = (right - left) * .14, (bottom - top) * .10
            if selected:
                left, top, right, bottom = max(0, left-dx), max(0, top-dy), min(1, right+dx), min(1, bottom+dy)
            boxes.append((person, selected, (left, top, right, bottom)))
    except (KeyError, TypeError, ValueError, UnicodeError) as exc:
        raise GenerationError("INVALID_METADATA") from exc
    if mask.size != size:
        raise GenerationError("MASK_SIZE_MISMATCH")
    if instances is not None:
        if instances.size != size:
            raise GenerationError("MASK_SIZE_MISMATCH")
        labels = [person.get("maskLabel") for person, _, _ in boxes]
        if any(type(label) is not int or not 1 <= label <= 3 for label in labels) or len(set(labels)) != len(labels):
            raise GenerationError("INVALID_INSTANCE_MASK")
        allowed = {0, 255, *labels}
        if any(count and label not in allowed for label, count in enumerate(instances.histogram())):
            raise GenerationError("INVALID_INSTANCE_MASK")
    edits = []
    claimed = Image.new("L", size, 0)
    for index, (person, selected, bounds) in enumerate(boxes):
        if not selected:
            continue
        left, top, right, bottom = bounds
        for other_index, (_, _, other) in enumerate(boxes):
            if other_index == index:
                continue
            if min(right, other[2]) > max(left, other[0]) and min(bottom, other[3]) > max(top, other[1]):
                raise GenerationError("PEOPLE_OVERLAP")
        region = Image.new("L", size, 0)
        ImageDraw.Draw(region).rectangle(
            (int(left*size[0]), int(top*size[1]), min(size[0]-1, math.ceil(right*size[0])-1),
             min(size[1]-1, math.ceil(bottom*size[1])-1)), fill=255,
        )
        region = ImageChops.multiply(mask.convert("L"), region)
        claimed = ImageChops.lighter(claimed, region)
        if instances is not None:
            label = person["maskLabel"]
            if not instances.point(lambda value: 255 if value == label else 0).getbbox():
                raise GenerationError("EMPTY_INSTANCE_MASK")
            # 即使人物框未相交，也保护伸出框外的旁人手脚与不确定重叠像素。
            allowed_region = instances.point(lambda value: 255 if value in (0, label) else 0)
            region = ImageChops.multiply(region, allowed_region)
        if not region.getbbox():
            raise GenerationError("EMPTY_EDIT_MASK")
        edits.append(PersonEdit(person["trackId"], person["character"], region))
    if not edits:
        raise GenerationError("NO_CHARACTER_SELECTED")
    if ImageChops.subtract(mask.convert("L"), claimed).getbbox():
        raise GenerationError("MASK_OUTSIDE_EDIT_REGION")
    return edits
