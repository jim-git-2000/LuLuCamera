"""检查正式角色素材；缺失允许占位，存在但无效则拒绝构建。"""
from pathlib import Path
import argparse
import json
from PIL import Image


def check(directory: Path, require_all: bool = False) -> dict:
    report = {}
    for name in ("lulu_a", "lulu_b"):
        for suffix in ("", "_reference"):
            path = directory / f"{name}{suffix}.png"
            if not path.exists():
                if require_all and not suffix:
                    raise ValueError(f"缺少 {path.name}")
                report[path.name] = "placeholder" if not suffix else "use_sprite"
                continue
            if path.stat().st_size > 8 * 1024 * 1024:
                raise ValueError(f"{path.name} 超过 8 MiB")
            with Image.open(path) as image:
                if image.format != "PNG" or min(image.size) < 64 or max(image.size) > 4096 or image.width * image.height > 8388608:
                    raise ValueError(f"{path.name} 格式或尺寸不符合要求")
                image.load()
                if not suffix:
                    alpha = image.convert("RGBA").getchannel("A")
                    low, high = alpha.getextrema()
                    if low == 255 or high == 0:
                        raise ValueError(f"{path.name} 需要透明背景和可见主体")
                report[path.name] = {"width": image.width, "height": image.height}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path(__file__).resolve().parents[1] / "assets/characters")
    parser.add_argument("--require-all", action="store_true")
    args = parser.parse_args()
    print(json.dumps(check(args.directory, args.require_all), ensure_ascii=False, indent=2))
