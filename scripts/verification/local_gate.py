#!/usr/bin/env python3
"""Release build and emulator gate for repair.py; uses the existing signing environment."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(os.environ["JARVIS_ATTEMPT_DIR"])
source = os.environ["JARVIS_SOURCE_COMMIT"]
env = dict(os.environ, GITHUB_SHA=source)
for key in ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"):
    if not env.get(key):
        sys.exit(f"Missing {key}; signing secrets must be provided by the runner")
subprocess.run(["gradle", "--no-daemon", "testReleaseUnitTest", "assembleRelease", "assembleReleaseAndroidTest"],
               cwd=root, env=env, check=True)
subprocess.run([sys.executable, "scripts/verification/android.py", "--serial", os.getenv("ANDROID_SERIAL", "emulator-5554"),
                "run", "--apk", "app/build/outputs/apk/release/app-release.apk", "--test-apk",
                "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk", "--out", str(out / "device"),
                "--source-commit", source, "--allow-emulator-reset"], cwd=root, env=env, check=True)
