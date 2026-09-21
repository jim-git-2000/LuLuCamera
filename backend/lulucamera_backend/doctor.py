"""服务主机配置检查；--warmup 明确下载并加载模型，默认不触发推理或下载。"""
import argparse
import json
from .characters import load_characters
from .config import settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--warmup", action="store_true")
    args = parser.parse_args()
    catalog = load_characters(settings.catalog_path, settings.asset_dir)
    if settings.generator == "diffusers":
        import torch
        if settings.device.startswith("cuda") and not torch.cuda.is_available():
            raise SystemExit("未检测到 CUDA GPU，请检查服务主机驱动和容器 GPU 配置")
        if not settings.model_id:
            raise SystemExit("缺少 LULU_MODEL_ID")
    report = {
        "mode": settings.generator, "model": settings.model_id,
        "device": settings.device, "timeout_seconds": settings.generation_timeout_seconds,
        "characters": {key: "reference_ready" if value.reference else "generic_placeholder" for key, value in catalog.items()},
    }
    if args.warmup:
        from .generators import create_generator
        generator = create_generator()
        if settings.generator == "diffusers" and any(c.reference for c in catalog.values()):
            generator.ensure_adapter()
        report["warmup"] = "model_loaded; generation quality still requires acceptance"
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
