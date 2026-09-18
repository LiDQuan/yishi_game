#!/usr/bin/env python3
"""Scan public Git candidates without printing matched secret values."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator


ROOT = Path(__file__).resolve().parents[2]
MAX_TEXT_BYTES = 5 * 1024 * 1024
ALLOW_MARKER = "public-scan: allow"

SENSITIVE_NAMES = {
    "local.properties",
    "keystore.properties",
    "signing.properties",
    "secrets.properties",
    "gitee_pat.txt",
    "credentials.json",
    "credentials.yml",
    "credentials.yaml",
    "cookies.txt",
    "session.json",
    "auth.json",
    "id_rsa",
    "id_ed25519",
    "id_ecdsa",
}
SENSITIVE_SUFFIXES = {
    ".jks",
    ".keystore",
    ".pem",
    ".key",
    ".p12",
    ".pfx",
    ".secret",
    ".token",
    ".logcat",
    ".dump",
    ".dmp",
    ".hprof",
}
SENSITIVE_PARTS = {"secrets", "private", "adb", "device-dumps", "uiautomator", "logcat", "crash-dumps", "tombstones"}
EXAMPLE_ENV_NAMES = {".env.example", "device.env.example"}

PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")  # public-scan: allow
BEARER = re.compile(r"(?i)\bbearer\s+(?P<value>[A-Za-z0-9._~+/=-]{12,})")
PRIVATE_IP = re.compile(r"(?<![\d.])(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(?![\d.])")
MAC_ADDRESS = re.compile(r"(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
LOCAL_USER_PATH = re.compile(r"/Users/(?P<value>[^/\s]+)/")  # public-scan: allow
URL_CREDENTIALS = re.compile(r"[a-z][a-z0-9+.-]*://[^\s/:]+:[^\s/@]+@", re.IGNORECASE)
SECRET_ASSIGNMENT = re.compile(
    r"(?i)[\"']?[A-Z][A-Z0-9_.-]*(?:TOKEN|PASSWORD|PASSWD|SECRET|API[_-]?KEY|PRIVATE[_-]?KEY|AUTHORIZATION|COOKIE|SESSION|KEY[_-]?ALIAS)[A-Z0-9_.-]*[\"']?"
    r"\s*[:=]\s*[\"']?(?P<value>[^\"'\s,}#]+)"
)
DEVICE_ASSIGNMENT = re.compile(
    r"(?i)[\"']?(?:ADB_DEVICE|PAD_SERIAL|DEVICE_SERIAL|PAD_IP|ADB_IP)[\"']?"
    r"\s*[:=]\s*[\"']?(?P<value>[^\"'\s,}#]+)"
)
COMMON_SECRETS = (
    ("aws-access-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("github-token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,255}\b")),
    ("gitlab-token", re.compile(r"\bglpat-[A-Za-z0-9_-]{20,255}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")),
)


@dataclass(frozen=True)
class Finding:
    source: str
    line: int
    rule: str


def git(*args: str) -> bytes:
    return subprocess.run(
        ["git", "-C", str(ROOT), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout


def placeholder(value: str) -> bool:
    value = value.strip().strip("\"'")
    upper = value.upper()
    if not value or value[0] in "$<[{(":
        return True
    if upper.startswith(("YOUR_", "EXAMPLE", "REDACTED", "CHANGE_ME", "CHANGEME", "PLACEHOLDER", "NOT_SET")):
        return True
    if upper in {"NONE", "NULL", "N/A"} or set(upper) <= {"X", "_", "-"}:
        return True
    if re.fullmatch(r"(?:\d{1,3}\.){2,3}x(?:\.x)*", value, re.IGNORECASE):
        return True
    return value.startswith(("os.", "env.", "process.env", "System.getenv", "getenv(", "settings."))


def path_rule(path: str) -> str | None:
    normalized = path.replace("\\", "/")
    parts = set(Path(normalized).parts)
    name = Path(normalized).name
    suffix = Path(normalized).suffix.lower()
    if name in EXAMPLE_ENV_NAMES:
        return None
    if name in SENSITIVE_NAMES or suffix in SENSITIVE_SUFFIXES:
        return "sensitive-file"
    if name.endswith(".env") or (name.startswith(".env.") and name != ".env.example"):
        return "environment-file"
    if parts & SENSITIVE_PARTS or normalized.endswith(".log"):
        return "private-runtime-data"
    return None


def scan_text(source: str, text: str) -> list[Finding]:
    findings: list[Finding] = []
    for number, line in enumerate(text.splitlines(), 1):
        if ALLOW_MARKER in line:
            continue
        if PRIVATE_KEY.search(line):
            findings.append(Finding(source, number, "private-key"))
        if URL_CREDENTIALS.search(line):
            findings.append(Finding(source, number, "url-credentials"))
        if PRIVATE_IP.search(line):
            findings.append(Finding(source, number, "private-ip"))
        if MAC_ADDRESS.search(line):
            findings.append(Finding(source, number, "mac-address"))
        local_path = LOCAL_USER_PATH.search(line)
        if local_path and local_path.group("value").lower() not in {"xxx", "user", "username", "your_user"}:
            findings.append(Finding(source, number, "local-user-path"))
        for rule, pattern in COMMON_SECRETS:
            if pattern.search(line):
                findings.append(Finding(source, number, rule))
        for rule, pattern in (("secret-assignment", SECRET_ASSIGNMENT), ("device-assignment", DEVICE_ASSIGNMENT), ("bearer-token", BEARER)):
            match = pattern.search(line)
            if match and not placeholder(match.group("value")):
                findings.append(Finding(source, number, rule))
    return findings


def decode_text(data: bytes) -> str | None:
    if len(data) > MAX_TEXT_BYTES or b"\0" in data[:8192]:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def public_candidates() -> Iterator[tuple[str, str, bytes]]:
    seen: set[tuple[str, bytes]] = set()
    paths = git("ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0")
    for raw_path in paths:
        if not raw_path:
            continue
        path = raw_path.decode("utf-8", "surrogateescape")
        full_path = ROOT / path
        if not full_path.exists() and not full_path.is_symlink():
            continue
        data = os.readlink(full_path).encode() if full_path.is_symlink() else full_path.read_bytes()
        digest = hashlib.sha256(data).digest()
        seen.add((path, digest))
        yield path, path, data

    for entry in git("ls-files", "-s", "-z").split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, blob, stage = metadata.decode().split()
        if stage != "0" or mode == "160000":
            continue
        path = raw_path.decode("utf-8", "surrogateescape")
        data = git("cat-file", "blob", blob)
        digest = hashlib.sha256(data).digest()
        if (path, digest) not in seen:
            yield f"INDEX:{path}", path, data


def historical_candidates() -> Iterator[tuple[str, str, bytes]]:
    seen: set[tuple[str, str]] = set()
    for commit in git("rev-list", "--all").decode().splitlines():
        message = git("show", "-s", "--format=%B", commit)
        yield f"HISTORY:{commit[:12]}:COMMIT_MESSAGE", "COMMIT_MESSAGE", message
        for entry in git("ls-tree", "-r", "-z", commit).split(b"\0"):
            if not entry:
                continue
            metadata, raw_path = entry.split(b"\t", 1)
            _mode, kind, blob = metadata.decode().split()
            if kind != "blob":
                continue
            path = raw_path.decode("utf-8", "surrogateescape")
            identity = (path, blob)
            if identity in seen:
                continue
            seen.add(identity)
            yield f"HISTORY:{commit[:12]}:{path}", path, git("cat-file", "blob", blob)


def scan(candidates: Iterable[tuple[str, str, bytes]]) -> tuple[list[Finding], int, int]:
    findings: list[Finding] = []
    scanned = skipped = 0
    for source, path, data in candidates:
        rule = path_rule(path)
        if rule:
            findings.append(Finding(source, 0, rule))
        text = decode_text(data)
        if text is None:
            skipped += 1
            continue
        scanned += 1
        findings.extend(scan_text(source, text))
    return findings, scanned, skipped


def self_test() -> None:
    token_name = "GITEE_" + "TOKEN"
    device_name = "ADB_" + "DEVICE"
    ip_name = "PAD_" + "IP"
    safe = "\n".join((f"{token_name}=YOUR_TOKEN_HERE", f"{device_name}=", f"{ip_name}=192.168.x.x"))
    unsafe = f"{token_name}=" + "a" * 40 + f"\n{device_name}=" + "device-1234"
    assert not scan_text("safe", safe)
    rules = {finding.rule for finding in scan_text("unsafe", unsafe)}
    assert {"secret-assignment", "device-assignment"} <= rules
    assert path_rule("local.properties") == "sensitive-file"
    assert path_rule(".env.example") is None
    print("Security scanner self-test passed.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true", help="also scan every blob reachable from Git history")
    parser.add_argument("--self-test", action="store_true", help="run built-in checks and exit")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    findings, current_count, current_skipped = scan(public_candidates())
    history_count = history_skipped = 0
    if args.history:
        historical, history_count, history_skipped = scan(historical_candidates())
        findings.extend(historical)

    unique = sorted(set(findings), key=lambda item: (item.source, item.line, item.rule))
    if unique:
        print("Public repository security scan failed:")
        for finding in unique:
            location = f"{finding.source}:{finding.line}" if finding.line else finding.source
            print(f"- {location} [{finding.rule}]")
        print("Matched values are intentionally hidden.")
        return 1

    print(
        "Public repository security scan passed: "
        f"{current_count} current/index text files, {history_count} historical text blobs; "
        f"{current_skipped + history_skipped} binary or oversized blobs skipped."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"Security scan could not run Git command: {error.cmd[2] if len(error.cmd) > 2 else 'unknown'}", file=sys.stderr)
        raise SystemExit(2)
