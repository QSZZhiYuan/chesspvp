#!/usr/bin/env python3
"""Prepare the pinned development dependency without adding it to Git."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import tempfile
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, help="Use a local compatible Touhou Little Maid JAR")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "libs/dependency-source.json").read_text(encoding="utf-8"))
    destination = root / "libs" / manifest["filename"]
    expected = manifest["sha256"]
    if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
        print(f"Dependency verified: {destination.name}")
        return
    url = manifest.get("download_url")
    if args.file is None and not url:
        parser.error("This port requires a locally built dependency. Pass --file PATH, or build with -Pmaid_jar=PATH.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".part", delete=False) as output:
            temporary = Path(output.name)
            if args.file is not None:
                with args.file.open("rb") as source:
                    shutil.copyfileobj(source, output)
            else:
                request = urllib.request.Request(url, headers={"User-Agent": "ChessPvp-dependency-setup/1.0"})
                with urllib.request.urlopen(request, timeout=120) as source:
                    shutil.copyfileobj(source, output)
        actual = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit(f"SHA-256 mismatch: expected {expected}, got {actual}. No dependency was installed.")
        temporary.replace(destination)
        print(f"Dependency installed and verified: {destination.name}")
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


if __name__ == "__main__":
    main()
