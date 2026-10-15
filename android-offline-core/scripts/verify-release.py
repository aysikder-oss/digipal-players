#!/usr/bin/env python3
"""Verify private release candidates, without publishing or accessing key material."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def output(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def elf_alignment(data, name):
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
        raise ValueError(f"{name}: unknown ELF class")
    for i in range(count):
        base = offset + i * size
        if struct.unpack_from(endian + "I", data, base)[0] == 1:
            alignment = struct.unpack_from(endian + align_type, data, base + align_offset)[0]
            if alignment < 16384:
                raise ValueError(f"{name}: PT_LOAD alignment {alignment} < 16384")


def fingerprint(text):
    return re.sub("[^a-f0-9]", "", text.lower())


def verify(args):
    root = Path(args.project)
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    apk = root / "app/build/outputs/apk/release/app-release.apk"
    aab = root / "app/build/outputs/bundle/release/app-release.aab"
    signing = output(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9:]+)", signing)
    if not match or fingerprint(match[1]) != fingerprint(args.certificate):
        raise ValueError("APK certificate differs from the existing public production installer")
    badging = output(str(tools / "aapt"), "dump", "badging", str(apk))
    for required in [f"package: name='{args.package}'", f"versionName='{args.version}'",
                     f"versionCode='{args.code}'", "targetSdkVersion:'36'"]:
        if required not in badging:
            raise ValueError(f"APK metadata missing {required}")
    if "application-debuggable" in badging:
        raise ValueError("Production APK is debuggable")
    if "android.intent.category.LEANBACK_LAUNCHER" not in output(
            str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"):
        raise ValueError("Missing TV launcher")
    output(str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk))
    verified_aab = output("jarsigner", "-verify", str(aab))
    if "jar verified." not in verified_aab:
        raise ValueError("AAB signature not verified")
    certificate = output("keytool", "-printcert", "-jarfile", str(aab))
    match = re.search(r"SHA256:\s*([a-fA-F0-9:]+)", certificate)
    if not match or fingerprint(match[1]) != fingerprint(args.certificate):
        raise ValueError("AAB certificate differs from the existing production installer")
    files = []
    for path in [apk, aab]:
        with zipfile.ZipFile(path) as archive:
            libs = [n for n in archive.namelist() if n.endswith(".so") and "/lib/" in "/" + n]
            abis = sorted({n.split("/lib/", 1)[1].split("/")[0]
                           for n in ["/" + n for n in libs]})
            if libs and not {"armeabi-v7a", "arm64-v8a"}.issubset(abis):
                raise ValueError("TV native payload must support both 32-bit and 64-bit ARM")
            for name in libs:
                elf_alignment(archive.read(name), name)
            if path == apk:
                dex = b"".join(archive.read(n) for n in archive.namelist() if n.endswith(".dex"))
                if b"https://digipalsignage.com" not in dex or b"offlinePlaybackContract" not in dex:
                    raise ValueError("Production server / explicit offline capability absent from APK")
        files.append({"name": path.name, "bytes": path.stat().st_size,
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                      "nativeLibraries": len(libs), "abis": abis})
    report = {"package": args.package, "version": args.version, "versionCode": args.code,
              "sourceRevision": os.environ.get("GITHUB_SHA"), "server": "https://digipalsignage.com",
              "certificateSHA256": fingerprint(args.certificate), "targetSdk": 36,
              "debuggable": False, "offlinePlaybackContract": 1, "files": files,
              "releaseApproved": False,
              "remainingGates": ["Publish compatible website and verify production manifest",
                                 "Physical offline/reconnect/schedule tests on both release variants",
                                 "Play Console version/signing/declarations and store review"]}
    (root / "release").mkdir(exist_ok=True)
    (root / "release/verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--code", required=True, type=int)
    parser.add_argument("--certificate", required=True)
    verify(parser.parse_args())
