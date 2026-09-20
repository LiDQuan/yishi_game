#!/usr/bin/env python3
"""Crop a normalized ROI from a private screenshot; never publishes automatically."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def parse_roi(value: str) -> tuple[float, float, float, float]:
    parts = tuple(float(part) for part in value.split(","))
    if len(parts) != 4 or not (0 <= parts[0] < parts[2] <= 1 and 0 <= parts[1] < parts[3] <= 1):
        raise argparse.ArgumentTypeError("ROI must be left,top,right,bottom within 0..1")
    return parts


def private_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    path.chmod(0o700)


def write_private(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def crop(args: argparse.Namespace) -> None:
    try:
        from PIL import Image
    except ImportError as error:
        raise SystemExit("Pillow is required: python3 -m pip install Pillow") from error

    image_path = Path(args.image).expanduser().resolve()
    output_dir = Path(args.output).expanduser().resolve()
    private_dir(output_dir)
    with Image.open(image_path) as image:
        viewport = args.viewport or (0, 0, image.width, image.height)
        left, top, right, bottom = args.roi
        box = (
            viewport[0] + round((viewport[2] - viewport[0]) * left),
            viewport[1] + round((viewport[3] - viewport[1]) * top),
            viewport[0] + round((viewport[2] - viewport[0]) * right),
            viewport[1] + round((viewport[3] - viewport[1]) * bottom),
        )
        result = image.crop(box)
        asset = output_dir / f"{args.id}.png"
        result.save(asset)
        asset.chmod(0o600)
    metadata = {
        "schemaVersion": 1,
        "templateSetVersion": args.template_set_version,
        "id": args.id,
        "version": 1,
        "pageId": args.page_id,
        "roi": dict(zip(("left", "top", "right", "bottom"), args.roi)),
        "threshold": args.threshold,
        "scalePolicy": "FIXED",
        "negative": args.negative,
        "asset": asset.name,
        "privacyReview": "PENDING",
    }
    write_private(output_dir / f"{args.id}.json", json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(f"Private ROI created: {asset}")
    print("Privacy review remains PENDING; this tool never copies files into the repository.")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(required=True)
    command = sub.add_parser("crop")
    command.add_argument("--image", required=True)
    command.add_argument("--output", default="~/.config/yishijieyongzhe/private/vision/templates")
    command.add_argument("--viewport", type=lambda value: tuple(int(part) for part in value.split(",")))
    command.add_argument("--roi", required=True, type=parse_roi)
    command.add_argument("--id", required=True)
    command.add_argument("--page-id", default="UNKNOWN")
    command.add_argument("--threshold", type=float, default=0.90)
    command.add_argument("--template-set-version", type=int, default=1)
    command.add_argument("--negative", action="store_true")
    command.set_defaults(run=crop)
    args = parser.parse_args()
    if args.viewport is not None and len(args.viewport) != 4:
        parser.error("viewport must be left,top,right,bottom")
    args.run(args)


if __name__ == "__main__":
    main()
