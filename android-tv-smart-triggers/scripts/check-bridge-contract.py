#!/usr/bin/env python3
"""ST-only current web contract gate; a method name alone is not compatibility."""
import pathlib
import re

source = (pathlib.Path(__file__).resolve().parent.parent
          / "app/src/main/java/com/nexuscast/player/MainActivity.java").read_text()
signatures = {
    "downloadMedia": 3, "getLocalMediaPath": 2, "getLocalMediaWebUrl": 2,
    "deleteMedia": 2, "deleteAllMedia": 1, "notifyPaired": 2,
    "captureScreenshot": 2, "setNativePlaylist": 2, "setPlaylistRevisionId": 2,
    "setSmartTriggerConfig": 2, "getConnectedDevices": 1, "startLearnMode": 2,
    "stopLearnMode": 1, "startBleScan": 1, "enableSmartTriggers": 1,
    "disableSmartTriggers": 1, "getSmartTriggerPairingCode": 1,
}
for name, arity in signatures.items():
    match = re.search(rf"@(?:android\.webkit\.)?JavascriptInterface\s+public\s+\w+\s+{name}\(([^)]*)\)\s*\{{([^}}]*)", source)
    if not match:
        raise SystemExit(f"{name}: missing exposed bridge method")
    parameters = [p.strip() for p in match[1].split(",") if p.strip()]
    if len(parameters) != arity or parameters[0] != "String token":
        raise SystemExit(f"{name}: incompatible authenticated signature {parameters}")
    if "isValidBridgeToken(token)" not in match[2]:
        raise SystemExit(f"{name}: missing token validation")
match = re.search(r"public void showNativeImage\(([^)]*)\)", source)
if not match or len(match[1].split(",")) != 7:
    raise SystemExit("showNativeImage requires seventh scoped content ID argument")
for name in ["__digipalNativeImageReady_", "__digipalNativeImageError_"]:
    if name not in source:
        raise SystemExit(f"Missing scoped callback: {name}")
if "getSmartTriggerToken(" in source:
    raise SystemExit("Never expose a token getter to arbitrary JavaScript frames")
print(f"ST bridge contract verified: {len(signatures)} authenticated methods and scoped image callbacks")
