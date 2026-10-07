#!/usr/bin/env python3
"""Fail closed on ST package, version, signing, SDK and 16-KB native layout."""
import argparse
import os
import re
import struct
import subprocess
import zipfile
from pathlib import Path


def verify_elf(data, name):
    if data[:4] != b"\x7fELF":
        raise ValueError(f"{name}: not ELF")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        offset = struct.unpack_from(endian + "Q", data, 32)[0]
        size, count = struct.unpack_from(endian + "HH", data, 54)
        align_offset, align_type = 48, "Q"
    elif data[4] == 1:
        offset = struct.unpack_from(endian + "I", data, 28)[0]
        size, count = struct.unpack_from(endian + "HH", data, 42)
        align_offset, align_type = 28, "I"
    else:
        raise ValueError(f"{name}: unsupported ELF class")
    for i in range(count):
        base = offset + i * size
        if struct.unpack_from(endian + "I", data, base)[0] == 1:
            alignment = struct.unpack_from(endian + align_type, data, base + align_offset)[0]
            if alignment < 16384:
                raise ValueError(f"{name}: PT_LOAD alignment {alignment}, requires >=16384")


def verify_version_fields(badging, version, version_code):
    if f"versionName='{version}'" not in badging or f"versionCode='{version_code}'" not in badging:
        raise ValueError("Version fields do not match the ST release")


def build_version_code(path=None):
    path = path or Path(__file__).resolve().parents[1] / "app/build.gradle"
    matches = re.findall(r"^\s*versionCode\s+(\d+)\s*$", Path(path).read_text(), re.MULTILINE)
    if len(matches) != 1 or int(matches[0]) <= 0:
        raise ValueError("Expected exactly one positive ST versionCode")
    return int(matches[0])


def verify(apk, tools, version, version_code, certificate, startup_test=False):
    signing = subprocess.check_output([f"{tools}/apksigner", "verify", "--verbose", "--print-certs", apk], text=True)
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9:]+)", signing)
    if not match or match[1].replace(":", "").lower() != certificate.replace(":", "").lower():
        raise ValueError("APK does not use the persistent ST-only certificate")
    badging = subprocess.check_output([f"{tools}/aapt", "dump", "badging", apk], text=True)
    package = 'com.nexuscast.player.startuptest' if startup_test else 'com.nexuscast.player'
    if f"package: name='{package}'" not in badging:
        raise ValueError("Wrong ST package")
    if startup_test:
        if 'Digipal ST Startup Test' not in badging:
            raise ValueError("Test installer must have a distinct launcher label")
        manifest = subprocess.check_output([f"{tools}/aapt", "dump", "xmltree", apk, "AndroidManifest.xml"], text=True)
        for component in ('BootReceiver', 'PackageUpdateReceiver', 'BootLaunchService'):
            blocks = re.split(r'\n\s*E: ', manifest)
            matches = [block for block in blocks if re.search(r'android:name.*' + component, block)]
            if len(matches) != 1 or not re.search(r'android:enabled.*\(type 0x12\)0x0\b', matches[0]):
                raise ValueError(f"Test installer must disable {component}")
    verify_version_fields(badging, version, version_code)
    if "targetSdkVersion:'36'" not in badging or "application-debuggable" in badging:
        raise ValueError("Requires SDK 36, non-debuggable production build")
    subprocess.check_call([f"{tools}/zipalign", "-c", "-P", "16", "4", apk])
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        from pathlib import Path
        import json
        import hashlib
        root = Path(__file__).resolve().parents[1]
        manifest = json.loads((root / "app/src/main/assets/mediapipe/0.10.32/manifest.json").read_text())
        for name, expected in manifest["files"].items():
            data = archive.read("assets/mediapipe/0.10.32/" + name)
            if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
                raise ValueError(f"Packaged gesture runtime corrupted: {name}")
        for name in archive.namelist():
            if name.startswith("lib/") and name.endswith(".so"):
                verify_elf(archive.read(name), name)
                libraries.append(name)
    purpose = 'startup-test' if startup_test else 'production'
    print(f"Verified ST {purpose} APK {version}: signing/package/SDK/16-KB; {len(libraries)} native libraries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--tools", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--startup-test", action="store_true")
    args = parser.parse_args()
    fingerprint = os.environ.get("ST_SIGNING_CERT_SHA256")
    if not fingerprint:
        raise SystemExit("ST_SIGNING_CERT_SHA256 required; never accept an arbitrary signing certificate")
    version_code = args.version_code if args.version_code is not None else build_version_code()
    verify(args.apk, args.tools, args.version, version_code, fingerprint, args.startup_test)
