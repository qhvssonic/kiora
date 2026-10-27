#!/usr/bin/env python3
"""Download the pinned int8 streaming Paraformer bundle (Chinese/English).

Usage: python scripts/prepare_streaming_paraformer.py OUTPUT_DIRECTORY
Select that entire directory in Companion's speech recognition settings.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

REVISION = "8e40c43232a1c5c66c82111efc5820d3accca11b"
REPO = "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
BASE = f"https://huggingface.co/{REPO}/resolve/{REVISION}"
FILES = {
    "encoder.int8.onnx": (165462184, "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a"),
    "decoder.int8.onnx": (71664561, "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f"),
    "tokens.txt": (75756, "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"),
}


def prepare(folder: Path) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    manifest = {"source": REPO, "revision": REVISION, "license": "Apache-2.0 (upstream model card)", "files": {}}
    for name, (size, expected) in FILES.items():
        target = folder / name
        if not target.is_file():
            partial = folder / (name + ".part")
            try:
                digest, count = hashlib.sha256(), 0
                with urlopen(f"{BASE}/{name}", timeout=120) as source, partial.open("wb") as sink:
                    while block := source.read(1024 * 1024):
                        count += len(block)
                        if count > size:
                            raise ValueError(f"{name}: larger than pinned size")
                        digest.update(block)
                        sink.write(block)
                if count != size or digest.hexdigest() != expected:
                    raise ValueError(f"{name}: downloaded size/hash mismatch")
                partial.replace(target)
            finally:
                partial.unlink(missing_ok=True)
        digest = hashlib.sha256()
        with target.open("rb") as source:
            while block := source.read(1024 * 1024):
                digest.update(block)
        if target.stat().st_size != size or digest.hexdigest() != expected:
            raise ValueError(f"{name}: existing size/hash mismatch; remove it and retry")
        manifest["files"][name] = {"bytes": size, "sha256": expected}
        print(f"Verified {name}: {size} bytes", flush=True)
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    # Keep the original model card, including its upstream source and license metadata.
    with urlopen(f"{BASE}/README.md", timeout=60) as source:
        (folder / "UPSTREAM_README.md").write_bytes(source.read(65536))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    prepare(parser.parse_args().folder)
