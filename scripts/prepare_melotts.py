#!/usr/bin/env python3
"""Download the exact, minimal MeloTTS bundle used by MeloTtsSynthesizer.

Usage: python scripts/prepare_melotts.py D:/path/to/output-folder
The output folder can be copied to the phone and selected in Companion settings.
"""

import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

REVISION = "a0d5c6a264c0ef92d70d8661d8cc502d79627cd6"
BASE = f"https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/resolve/{REVISION}"
# Fixed-revision sizes from the Hugging Face model API and verified SHA-256 digests.
# The ONNX digest also matches its upstream LFS pointer.
FILES = {
    "LICENSE": (1053, "88a50e5a02bbc2a5c2f084dc19da751aa97b1690f5fda76cd8005c8634d1ca70"),
    "date.fst": (59154, "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
    "lexicon.txt": (6837671, "7236884b02435ac5d10cf69b4be40a61b45aa676b5300f0e412f185748fee528"),
    "model.int8.onnx": (53517430, "f085f5079e05f039b800aeb542f5253c26a303211b0c6465d0d9387977855a63"),
    "new_heteronym.fst": (21974, "ca14b2127e27baa571664e4bb791e143e7425f56a6bc29db08d74f97e6aa4e29"),
    "number.fst": (64482, "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
    "phone.fst": (88630, "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
    "tokens.txt": (655, "d18664a7e12bd7ea1022ddaf951e534e136815016c5a809d6b64156bffb4369d"),
}


def prepare(folder: Path, precision: str = "int8") -> None:
    folder.mkdir(parents=True, exist_ok=True)
    files = dict(FILES)
    if precision == "fp32":
        del files["model.int8.onnx"]
        files["model.onnx"] = (170429550, "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d")
    elif precision != "int8":
        raise ValueError("Unsupported precision")
    manifest = {"source": "csukuangfj/vits-melo-tts-zh_en", "revision": REVISION, "precision": precision, "files": {}}
    for name, (size, expected_sha256) in files.items():
        target = folder / name
        if not target.is_file() or target.stat().st_size != size:
            partial = folder / (name + ".part")
            digest = hashlib.sha256()
            count = 0
            try:
                with urlopen(f"{BASE}/{name}", timeout=60) as source, partial.open("wb") as sink:
                    while block := source.read(1024 * 1024):
                        count += len(block)
                        if count > size:
                            raise ValueError(f"{name}: larger than the pinned size")
                        digest.update(block)
                        sink.write(block)
                if count != size or (expected_sha256 and digest.hexdigest() != expected_sha256):
                    raise ValueError(f"{name}: size or digest does not match the pinned source")
                partial.replace(target)
            finally:
                partial.unlink(missing_ok=True)
        digest = hashlib.sha256()
        with target.open("rb") as source:
            while block := source.read(1024 * 1024):
                digest.update(block)
        actual = digest.hexdigest()
        if target.stat().st_size != size or actual != expected_sha256:
            raise ValueError(f"{name}: existing file does not match the pinned source")
        manifest["files"][name] = {"bytes": size, "sha256": actual}
        print(f"{name}: {size} bytes, sha256={actual}")
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Ready: {folder}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--precision", choices=["int8", "fp32"], default="int8")
    args = parser.parse_args()
    prepare(args.folder, args.precision)
