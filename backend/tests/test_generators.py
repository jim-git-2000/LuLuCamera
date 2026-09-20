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
