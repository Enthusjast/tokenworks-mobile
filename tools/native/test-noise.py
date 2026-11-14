#!/usr/bin/env python3
"""Narrow actual-engine proof; Android instrumentation is a separately scheduled gate."""
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def run(args, cwd=ROOT, env=None):
    subprocess.run(args, cwd=cwd, env=env, check=True)


def main():
    version = subprocess.check_output(["node", "--version"], text=True).strip()
    if not version.startswith("v24."):
        raise SystemExit("Node24 required for the production binding proof")
    env = os.environ.copy()
    env["CARGO_TARGET_DIR"] = str(ROOT / ".local/noise/target/tests")
    env["CARGO_BUILD_JOBS"] = "2"
    run(["cargo", "+1.96.0", "test", "--manifest-path", "native/noise-carrier/Cargo.toml", "--locked", "--release", "--no-default-features"], env=env)
    run(["python3", "tools/native/build-noise.py", "node"])
    run(["./gradlew", "--max-workers=2", "-Dorg.gradle.parallel=false", "-PjvmOnly=true", ":core:crypto:test", "--tests", "cn.edu.ustc.tokenworks.E2eSessionTest", ":core:crypto:noiseInterop"], ROOT / "android")
    run(["python3", "tools/native/verify-jni-api.py", "android/core/crypto/build/classes/kotlin/main",
         ".local/android-sdk/platforms/android-36/data/api-versions.xml"])


if __name__ == "__main__":
    main()
