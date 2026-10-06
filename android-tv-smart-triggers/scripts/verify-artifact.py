#!/usr/bin/env python3
"""Fail closed on ST package, version, signing, SDK and 16-KB native layout."""
import argparse
import os
import re
import struct
import subprocess
import zipfile


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


def verify(apk, tools, version, certificate):
    signing = subprocess.check_output([f"{tools}/apksigner", "verify", "--verbose", "--print-certs", apk], text=True)
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9:]+)", signing)
    if not match or match[1].replace(":", "").lower() != certificate.replace(":", "").lower():
        raise ValueError("APK does not use the persistent ST-only certificate")
    badging = subprocess.check_output([f"{tools}/aapt", "dump", "badging", apk], text=True)
    if "package: name='com.nexuscast.player'" not in badging:
        raise ValueError("Wrong production package")
    if f"versionName='{version}'" not in badging or "versionCode='42'" not in badging:
        raise ValueError("Version fields do not match the ST release")
    if "targetSdkVersion:'36'" not in badging or "application-debuggable" in badging:
        raise ValueError("Requires SDK 36, non-debuggable production build")
    subprocess.check_call([f"{tools}/zipalign", "-c", "-P", "16", "4", apk])
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("lib/") and name.endswith(".so"):
                verify_elf(archive.read(name), name)
                libraries.append(name)
    print(f"Verified ST production APK {version}: signing/package/SDK/16-KB; {len(libraries)} native libraries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--tools", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    fingerprint = os.environ.get("ST_SIGNING_CERT_SHA256")
    if not fingerprint:
        raise SystemExit("ST_SIGNING_CERT_SHA256 required; never accept an arbitrary signing certificate")
    verify(args.apk, args.tools, args.version, fingerprint)
