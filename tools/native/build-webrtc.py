#!/usr/bin/env python3
"""Build the source-pinned, data-only Android RTC SDK; no runtime download or binary fallback."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "native/webrtc/source.lock.json"
PATCH = ROOT / "native/webrtc/bounded-carrier.patch"
WORK = ROOT / ".local/webrtc-build"
ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}


def run(args, cwd, env=None):
    subprocess.run([str(value) for value in args], cwd=cwd, env=env, check=True)


def capture(args, cwd):
    return subprocess.check_output([str(value) for value in args], cwd=cwd, text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checkout(spec):
    depot = WORK / "depot-pinned" if (WORK / "depot-pinned").exists() else WORK / "depot_tools"
    cache = WORK / "src"
    source = WORK / "buildroot/src"
    WORK.mkdir(parents=True, exist_ok=True)
    if not depot.exists():
        run(["git", "clone", "--no-checkout", spec["depotToolsRepository"], depot], ROOT)
        pinned = WORK / "depot-pinned"
        run(["git", "-C", depot, "worktree", "add", "--detach", pinned, spec["depotToolsCommit"]], ROOT)
        depot = pinned
    elif capture(["git", "rev-parse", "HEAD"], depot) != spec["depotToolsCommit"]:
        raise SystemExit("depot_tools cache revision differs from source.lock.json")
    if not source.exists():
        if not cache.exists():
            run(["git", "clone", "--no-checkout", "--depth=1", "--branch", spec["sourceBranch"], spec["sourceRepository"], cache], ROOT)
        run(["git", "fetch", "--depth=1", "origin", spec["sourceCommit"]], cache)
        source.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "worktree", "add", "--detach", source, spec["sourceCommit"]], cache)
    if capture(["git", "rev-parse", "HEAD"], source) != spec["sourceCommit"]:
        raise SystemExit("RTC source cache revision differs from source.lock.json")
    return depot, source


def prepare(spec, depot, source, env):
    synced = source.parent / "dependencies-synced.json"
    hooks = source.parent / "hooks-ready.json"
    expected = {"source": spec["sourceCommit"], "depot": spec["depotToolsCommit"]}
    if not synced.exists() or json.loads(synced.read_text()) != expected:
        (source.parent / ".gclient").write_text(
            "solutions = [{'name':'src','url':" + repr(spec["sourceRepository"]) + ", 'managed':False, 'custom_deps':{}, "
            "'custom_vars':{'checkout_configuration':'small','checkout_instrumented_libraries':False,'download_remoteexec_cfg':False}}]\n"
            "target_os = ['android']\n")
        run([depot / "gclient", "sync", "--nohooks", "--no-history", "--shallow", "--jobs=2"], source.parent, env)
        synced.write_text(json.dumps(expected, sort_keys=True) + "\n")
    if not hooks.exists() or json.loads(hooks.read_text()) != expected:
        run([depot / "gclient", "runhooks", "--jobs=2"], source.parent, env)
        hooks.write_text(json.dumps(expected, sort_keys=True) + "\n")
    ndk = source / "third_party/android_toolchain/ndk"
    descriptions = list((source.parent / ".cipd/pkgs").glob("*/description.json"))
    matches = [path.parent for path in descriptions if json.loads(path.read_text()).get("subdir") == "src/third_party/android_toolchain/ndk"]
    if len(matches) != 1 or (matches[0] / "_current").resolve().name != spec["ndkCipdInstance"]:
        raise SystemExit("RTC NDK immutable CIPD instance differs from source.lock.json")
    header = (ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/android/ndk-version.h").read_text()
    for key, expected in spec["ndkHeader"].items():
        match = re.search(r"^#define __NDK_" + key + r"__ ([0-9]+)$", header, re.MULTILINE)
        if not match or int(match.group(1)) != expected:
            raise SystemExit("RTC NDK actual header version differs from source.lock.json")
    if digest(PATCH) != spec["patchSha256"]:
        raise SystemExit("RTC resource patch digest differs from source.lock.json")
    already_applied = subprocess.run(["git", "apply", "--reverse", "--check", str(PATCH)], cwd=source,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not already_applied:
        run(["git", "apply", "--check", PATCH], source)
        run(["git", "apply", PATCH], source)
    for name, expected_hash in spec["patchedFiles"].items():
        if digest(source / name) != expected_hash:
            raise SystemExit("RTC patched build input differs: " + name)


def elf_facts(data, abi):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Android library must be little-endian ELF: " + abi)
    bits, machine = ABIS[abi]
    if data[4] != bits or struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError("Wrong Android ABI: " + abi)
    phoff = struct.unpack_from("<Q" if bits == 2 else "<I", data, 32 if bits == 2 else 28)[0]
    size, count = struct.unpack_from("<HH", data, 54 if bits == 2 else 42)
    alignments = []
    apis = []
    build_ids = []
    for index in range(count):
        at = phoff + index * size
        kind = struct.unpack_from("<I", data, at)[0]
        if bits == 2:
            offset, vaddr = struct.unpack_from("<QQ", data, at + 8)
            file_size = struct.unpack_from("<Q", data, at + 32)[0]
            alignment = struct.unpack_from("<Q", data, at + 48)[0]
        else:
            offset, vaddr = struct.unpack_from("<II", data, at + 4)
            file_size = struct.unpack_from("<I", data, at + 16)[0]
            alignment = struct.unpack_from("<I", data, at + 28)[0]
        if kind == 1:
            if alignment < 16384 or offset % 16384 != vaddr % 16384:
                raise ValueError("RTC ELF is not 16KiB load-aligned: " + abi)
            alignments.append(alignment)
        if kind == 4:
            cursor = offset
            while cursor + 12 <= offset + file_size:
                names, descs, note_type = struct.unpack_from("<III", data, cursor)
                cursor += 12
                name = data[cursor:cursor + names].rstrip(b"\0")
                cursor += (names + 3) & ~3
                desc = data[cursor:cursor + descs]
                cursor += (descs + 3) & ~3
                if name == b"Android" and note_type == 1 and len(desc) >= 4:
                    apis.append(struct.unpack_from("<I", desc)[0])
                if name == b"GNU" and note_type == 3 and desc:
                    build_ids.append(desc.hex())
    if not alignments or apis != [26] or len(build_ids) != 1:
        raise ValueError("RTC library does not prove the locked API26 target: " + abi + " " + repr(apis))
    return {"sha256": hashlib.sha256(data).hexdigest(), "loadAlignments": alignments, "androidApi": apis[0], "buildId": build_ids[0]}


def package(spec, source, raw, output):
    license_file = raw.parent / "LICENSE.md"
    def write_entry(name, data):
        metadata = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        metadata.external_attr = 0o644 << 16
        target.writestr(metadata, data)
    if not license_file.is_file() or not license_file.stat().st_size:
        raise SystemExit("Upstream dependency license generation is required")
    output.parent.mkdir(parents=True, exist_ok=True)
    facts = {}
    with tempfile.TemporaryDirectory(prefix="rtc-package-", dir=output.parent) as temporary:
        staging = Path(temporary) / "staging.aar"
        aligned = Path(temporary) / "aligned.aar"
        with zipfile.ZipFile(raw) as original, zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_STORED) as target:
            manifest = original.read("AndroidManifest.xml")
            if b'android:minSdkVersion="26"' not in manifest or b"uses-permission" in manifest:
                raise ValueError("RTC AAR manifest is not the data-only API26 manifest")
            for entry in original.infolist():
                data = original.read(entry.filename)
                if entry.filename == "classes.jar":
                    with zipfile.ZipFile(io.BytesIO(data)) as classes:
                        headers = [classes.read(item)[:8] for item in classes.namelist() if item.endswith(".class")]
                    if not headers or any(len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe" or struct.unpack_from(">H", header, 6)[0] > 61 for header in headers):
                        raise ValueError("RTC Java bytecode must support the app's Java 17 toolchain")
                if entry.filename.startswith("jni/") and entry.filename.endswith(".so"):
                    abi = entry.filename.split("/")[1]
                    facts[abi] = elf_facts(data, abi)
                write_entry(entry.filename, data)
            if set(facts) != set(ABIS):
                raise ValueError("RTC AAR must contain all four locked Android ABIs")
            write_entry("META-INF/LICENSE.txt", b"Copyright 2026 TokenWorks contributors. Modifications use the BSD-3-Clause license reproduced below.\n\n" + license_file.read_bytes())
            write_entry("META-INF/tokenworks-rtc-build.json", json.dumps({
                "version": spec["version"], "sourceCommit": spec["sourceCommit"], "patchSha256": spec["patchSha256"],
                "ndkVersion": spec["ndkVersion"], "dataOnly": True, "abis": facts,
                "ndkRequestedTag": spec["ndkRequestedTag"], "ndkCipdInstance": spec["ndkCipdInstance"], "ndkHeader": spec["ndkHeader"],
            }, sort_keys=True).encode())
        zipalign = ROOT / ".local/android-sdk/build-tools/35.0.0/zipalign"
        run([zipalign, "-P", "16", "-f", "4", staging, aligned], ROOT)
        with zipfile.ZipFile(aligned) as built:
            raw_zip = aligned.read_bytes()
            for entry in built.infolist():
                if entry.filename.endswith(".so"):
                    name_size, extra_size = struct.unpack_from("<HH", raw_zip, entry.header_offset + 26)
                    data_start = entry.header_offset + 30 + name_size + extra_size
                    if entry.compress_type != zipfile.ZIP_STORED or data_start % 16384:
                        raise ValueError("RTC AAR native ZIP entries are not 16KiB aligned")
        shutil.copyfile(aligned, output)
    (output.parent / "build-proof.json").write_text(json.dumps({"version": spec["version"], "aarSha256": digest(output), "abis": facts}, indent=2) + "\n")

def test_budget(depot, source, spec, env):
    output = source / "out_tokenworks_budget"
    run([depot / "vpython3", depot / "gn.py", "gen", output,
         "--args=" + " ".join(spec["budgetTestGnArgs"])], source, env)
    run([source / "third_party/siso/cipd/siso", "ninja", "-C", output,
         "-local_jobs=2", "-offline", "-re_exec_enable=false", "-fs_state_compression_threads=2",
         "net/dcsctp:dcsctp_unittests"], source, env)
    run([output / "dcsctp_unittests", "--gtest_filter=*NativeCarrier*"], source, env)



def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "android/app/build/generated/webrtc/tokenworks-webrtc.aar")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--prepare-only", action="store_true")
    modes.add_argument("--test-budget", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Linux":
        raise SystemExit("The locked RTC source toolchain requires a Linux build host")
    spec = json.loads(SPEC.read_text())
    depot, source = checkout(spec)
    env = os.environ.copy()
    env.update({"PATH": str(depot) + os.pathsep + env["PATH"], "DEPOT_TOOLS_UPDATE": "0", "DEPOT_TOOLS_METRICS": "0"})
    env["SOURCE_DATE_EPOCH"] = str(spec["sourceDateEpoch"])
    prepare(spec, depot, source, env)
    if args.prepare_only:
        return
    if args.test_budget:
        test_budget(depot, source, spec, env)
        return
    raw = source / "out_tokenworks/tokenworks-webrtc-raw.aar"
    raw.parent.mkdir(parents=True, exist_ok=True)
    builder = source / "tools_webrtc/android/build_aar.py"
    invocation = ("import sys; sys.argv[0]=" + repr(str(builder)) + "; sys.path.insert(0," + repr(str(builder.parent)) +
        "); import build_aar; build_aar.BuildAar(" + repr(str(raw.parent)) + "," + repr(list(ABIS)) + "," + repr(str(raw)) +
        ",extra_gn_args=" + repr(spec["gnArgs"]) + ",extra_ninja_switches=" +
        repr(["-local_jobs=2", "-offline", "-re_exec_enable=false", "-fs_state_compression_threads=2"]) + ")")
    run([depot / "vpython3", "-c", invocation], source, env)
    package(spec, source, raw, args.output.resolve())


if __name__ == "__main__":
    main()
