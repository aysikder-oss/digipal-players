#!/usr/bin/env python3
"""Offline build gate: both MediaPipe loader/binary pairs must match the pinned package."""
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/mediapipe/0.10.32"
manifest = json.loads((ASSETS / "manifest.json").read_text())
assert manifest["version"] == "0.10.32"
assert manifest["source"] == "https://registry.npmjs.org/@mediapipe/tasks-vision/-/tasks-vision-0.10.32.tgz"
names = {f"vision_{variant}_internal.{ext}" for variant in ("wasm", "wasm_nosimd") for ext in ("js", "wasm")}
assert set(manifest["files"]) == names, "Missing SIMD or non-SIMD resource"
assert (ASSETS / "LICENSE").stat().st_size > 10000, "Missing upstream license"
java = (ROOT / "app/src/main/java/com/nexuscast/player/GestureRuntimeAssets.java").read_text()
assert 'VERSION = "0.10.32"' in java
for name, expected in manifest["files"].items():
    assert f'"{name}"' in java, f"Resource not allowlisted: {name}"
    data = (ASSETS / name).read_bytes()
    assert len(data) == expected["bytes"], f"Wrong resource size: {name}"
    assert hashlib.sha256(data).hexdigest() == expected["sha256"], f"Resource hash mismatch: {name}"
    if name.endswith(".wasm"):
        assert data[:8] == b"\0asm\x01\0\0\0", f"Invalid WASM: {name}"
    else:
        assert b"var ModuleFactory=" in data and b"wasmExports=await (createWasm())" in data, f"Invalid loader: {name}"
print("Gesture SDK/runtime pin 0.10.32: all four packaged resources verified")
subprocess.run(["node", "--test", "tests/gesture-runtime.test.cjs"], cwd=ROOT, check=True)
