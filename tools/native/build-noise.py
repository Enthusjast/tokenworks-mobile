#!/usr/bin/env python3
"""Build the packaged fixed-suite engine; never installs or downloads at runtime."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CRATE = ROOT / "native/noise-carrier/Cargo.toml"
RUST = "1.96.0"
NDK_VERSION = "28.2.13676358"
ANDROID = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
    "x86": ("i686-linux-android", "i686-linux-android"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
}


def run(args, env=None):
    subprocess.run(args, cwd=ROOT, env=env, check=True)


def library():
    return {"Windows": "tokenworks_noise.dll", "Darwin": "libtokenworks_noise.dylib", "Linux": "libtokenworks_noise.so"}[platform.system()]


def copy(source, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output)
    output.chmod(0o644)


def build(feature, target_dir, target=None, linker=None):
    env = os.environ.copy()
    env["CARGO_TARGET_DIR"] = str(target_dir)
    env["CARGO_BUILD_JOBS"] = "2"
    if linker:
        env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(linker)
        env[f"CC_{target.replace('-', '_')}"] = str(linker)
        env[f"AR_{target.replace('-', '_')}"] = str(linker.parent / ("llvm-ar.exe" if os.name == "nt" else "llvm-ar"))
        env["CARGO_ENCODED_RUSTFLAGS"] = "-C\x1flink-arg=-Wl,-z,max-page-size=16384"
    command = ["cargo", f"+{RUST}", "build", "--manifest-path", str(CRATE), "--locked", "--release", "--no-default-features", "--features", feature]
    if target:
        command += ["--target", target]
    run(command, env)
    return target_dir / target / "release/libtokenworks_noise.so" if target else target_dir / "release" / library()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["node", "jvm", "android", "all"])
    parser.add_argument("--android-output", type=Path, default=ROOT / "android/app/build/generated/noise/jniLibs")
    parser.add_argument("--node-output", type=Path, default=ROOT / "host/remote-control/lib/native/tokenworks_noise.node")
    args = parser.parse_args()
    work = ROOT / ".local/noise"
    if args.kind in ("node", "all"):
        copy(build("node", work / "target/node"), args.node_output.resolve())
    if args.kind in ("jvm", "all"):
        copy(build("jni", work / "target/jvm"), work / "jvm" / library())
    if args.kind in ("android", "all"):
        ndk = ROOT / ".local/android-sdk/ndk" / NDK_VERSION
        properties = (ndk / "source.properties").read_text()
        if f"Pkg.Revision = {NDK_VERSION}" not in properties:
            raise SystemExit("Pinned project-local NDK revision required")
        host = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64", "Windows": "windows-x86_64"}[platform.system()]
        toolchain = ndk / "toolchains/llvm/prebuilt" / host
        installed = subprocess.check_output(["rustup", "target", "list", "--installed", "--toolchain", RUST], text=True).splitlines()
        for abi, (target, clang) in ANDROID.items():
            if target not in installed:
                raise SystemExit(f"Install build target first: rustup target add --toolchain {RUST} {target}")
            linker = toolchain / "bin" / (clang + "26-clang" + (".cmd" if os.name == "nt" else ""))
            result = build("jni", work / "target/android", target, linker)
            output = args.android_output.resolve() / abi / "libtokenworks_noise.so"
            copy(result, output)
        run(["python3" if os.name != "nt" else "python", str(ROOT / "tools/native/verify-noise.py"), str(args.android_output.resolve()), str(toolchain)])


if __name__ == "__main__":
    main()
