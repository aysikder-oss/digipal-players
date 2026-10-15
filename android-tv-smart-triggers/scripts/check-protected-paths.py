#!/usr/bin/env python3
"""Bound the explicitly approved two-player offline backport; protect other apps."""
import subprocess

BASE = "f254ec54a5be43b159477ca4da9a01e56f38dfc5"
allowed_workflows = {".github/workflows/build-android-tv-smart-triggers.yml",
                     ".github/workflows/build-android-tv.yml"}
ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
changed = subprocess.check_output(["git", "diff", "--name-only", BASE, "--"], cwd=ROOT, text=True).splitlines()
untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
allowed_prefixes = ("android-tv-smart-triggers/", "android-tv/", "android-offline-core/")
bad = [p for p in changed + untracked if not p.startswith(allowed_prefixes) and p not in allowed_workflows]
if bad:
    raise SystemExit("Protected files changed: " + ", ".join(bad))
print("Offline backport is restricted to both approved Android players and shared offline core")
