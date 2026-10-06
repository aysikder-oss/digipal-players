#!/usr/bin/env python3
"""Check this ST backport against its immutable known-good reference commit."""
import subprocess

BASE = "94b7e5c5ec366ea2b755817636125055225f9c1c"
allowed_workflows = {".github/workflows/build-android-tv-smart-triggers.yml"}
ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
changed = subprocess.check_output(["git", "diff", "--name-only", BASE, "--"], cwd=ROOT, text=True).splitlines()
untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
bad = [p for p in changed + untracked if not p.startswith("android-tv-smart-triggers/") and p not in allowed_workflows]
if bad:
    raise SystemExit("Protected files changed: " + ", ".join(bad))
baseline = subprocess.check_output(["git", "rev-parse", f"{BASE}:android-tv"], cwd=ROOT, text=True).strip()
current = subprocess.check_output(["git", "rev-parse", "HEAD:android-tv"], cwd=ROOT, text=True).strip()
if baseline != current:
    raise SystemExit("Standard Android TV tree differs from the captured baseline")
print("Protected files unchanged; standard Android TV tree:", baseline)
