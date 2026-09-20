#!/usr/bin/env python3
"""Collect a private, local Pad snapshot without exposing ADB identifiers."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path


PRIVATE_ROOT = Path.home() / ".config" / "yishijieyongzhe" / "private" / "pad-reports"
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
FOREGROUND_PATTERNS = (
    re.compile(r"mCurrentFocus=.*?\s([A-Za-z][\w.]*)/[A-Za-z0-9_.$]+"),
    re.compile(r"mFocusedApp=.*?\s([A-Za-z][\w.]*)/[A-Za-z0-9_.$]+"),
)
BOUNDS_RE = re.compile(r"(?:frame|bounds|mBounds)=?\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
ACTIVITY_BOUNDS_RE = re.compile(r"cmp=([A-Za-z][\w.]*)/[\w.$]+\s+bnds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


class InspectorError(RuntimeError):
    pass


def _run(*args: str, binary: bool = False) -> str | bytes:
    result = subprocess.run(
        ["adb", *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=not binary,
    )
    if result.returncode:
        raise InspectorError("ADB command failed; inspect the local terminal for the non-sensitive command name")
    return result.stdout


def require_one_ready_device(devices_output: str) -> None:
    ready = [line for line in devices_output.splitlines()[1:] if line.strip().endswith("\tdevice")]
    others = [line for line in devices_output.splitlines()[1:] if line.strip() and not line.strip().endswith("\tdevice")]
    if len(ready) != 1 or others:
        raise InspectorError("Expected exactly one ready ADB device and no unauthorized/offline devices")


def parse_foreground(window_dump: str) -> str | None:
    for pattern in FOREGROUND_PATTERNS:
        match = pattern.search(window_dump)
        if match:
            return match.group(1)
    return None


def parse_window_bounds(window_dump: str, package_name: str | None) -> tuple[int, int, int, int] | None:
    if not package_name:
        return None
    lines = window_dump.splitlines()
    for index, line in enumerate(lines):
        if package_name not in line:
            continue
        neighborhood = "\n".join(lines[index : index + 18])
        for match in BOUNDS_RE.finditer(neighborhood):
            bounds = tuple(int(value) for value in match.groups())
            if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return bounds
    return None


def parse_activity_bounds(activity_dump: str, package_name: str | None) -> tuple[int, int, int, int] | None:
    if not package_name:
        return None
    for match in ACTIVITY_BOUNDS_RE.finditer(activity_dump):
        if match.group(1) != package_name:
            continue
        bounds = tuple(int(value) for value in match.groups()[1:])
        if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
            return bounds
    return None


def _prop(name: str) -> str:
    return str(_run("shell", "getprop", name)).strip()


def write_private_text(path: Path, value: str) -> None:
    path.write_text(value, encoding="utf-8")
    path.chmod(0o600)


def write_private_bytes(path: Path, value: bytes) -> None:
    path.write_bytes(value)
    path.chmod(0o600)


def _parse_wm(text: str) -> dict[str, str | None]:
    values: dict[str, str | None] = {"physical_size": None, "override_size": None, "density": None}
    for line in text.splitlines():
        lowered = line.lower()
        value = line.partition(":")[2].strip() or None
        if "physical size" in lowered:
            values["physical_size"] = value
        elif "override size" in lowered:
            values["override_size"] = value
        elif "density" in lowered:
            values["density"] = value
    return values


def capture(label: str, target_package: str | None) -> Path:
    require_one_ready_device(str(_run("devices")))
    if target_package and not PACKAGE_RE.fullmatch(target_package):
        raise InspectorError("Target package format is invalid")

    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S-%z")
    report_dir = PRIVATE_ROOT / stamp
    report_dir.mkdir(parents=True, exist_ok=False)
    os.chmod(report_dir, 0o700)

    window_dump = str(_run("shell", "dumpsys", "window", "windows"))
    activity_dump = str(_run("shell", "dumpsys", "activity", "activities"))
    display_dump = str(_run("shell", "dumpsys", "display"))
    wm_dump = str(_run("shell", "wm", "size")) + "\n" + str(_run("shell", "wm", "density"))
    foreground = parse_foreground(window_dump)
    inspected_package = target_package or foreground
    bounds = parse_window_bounds(window_dump, inspected_package) or parse_activity_bounds(activity_dump, inspected_package)

    write_private_text(report_dir / "window.txt", window_dump)
    write_private_text(report_dir / "activity.txt", activity_dump)
    write_private_text(report_dir / "display.txt", display_dump)
    write_private_bytes(report_dir / "screen.png", bytes(_run("exec-out", "screencap", "-p", binary=True)))

    _run("shell", "uiautomator", "dump", "/sdcard/yishi-window.xml")
    ui_xml = str(_run("shell", "cat", "/sdcard/yishi-window.xml"))
    _run("shell", "rm", "/sdcard/yishi-window.xml")
    write_private_text(report_dir / "window.xml", ui_xml)

    wm = _parse_wm(wm_dump)
    rotation_match = re.search(r"(?:mCurrentOrientation|orientation|mRotation)\s*[=:]\s*(\d+)", display_dump)
    report = {
        "schema_version": 1,
        "captured_at": datetime.now().astimezone().isoformat(),
        "label": label,
        "device": {
            "manufacturer": _prop("ro.product.manufacturer"),
            "model": _prop("ro.product.model"),
            "android_release": _prop("ro.build.version.release"),
            "sdk": _prop("ro.build.version.sdk"),
        },
        "display": {**wm, "orientation": rotation_match.group(1) if rotation_match else None},
        "foreground": {"package": foreground},
        "window": {
            "package": inspected_package,
            "bounds": list(bounds) if bounds else None,
            "width": bounds[2] - bounds[0] if bounds else None,
            "height": bounds[3] - bounds[1] if bounds else None,
        },
        "artifacts": ["window.txt", "activity.txt", "display.txt", "window.xml", "screen.png"],
    }
    write_private_text(report_dir / "report.json", json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Private report created: {report_dir}")
    return report_dir


def compare(report_paths: list[Path]) -> None:
    rows = []
    for path in report_paths:
        report_file = path / "report.json" if path.is_dir() else path
        data = json.loads(report_file.read_text(encoding="utf-8"))
        rows.append({"label": data.get("label"), "bounds": data.get("window", {}).get("bounds")})
    print(json.dumps({"samples": rows, "bounds_changed": len({str(row["bounds"]) for row in rows}) > 1}, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    capture_parser = subparsers.add_parser("capture")
    capture_parser.add_argument("--label", required=True)
    capture_parser.add_argument("--target-package", default=os.environ.get("TARGET_GAME_PACKAGE"))
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("reports", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "capture":
            capture(args.label, args.target_package)
        else:
            compare(args.reports)
        return 0
    except (InspectorError, OSError, json.JSONDecodeError) as error:
        print(f"Inspector error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
