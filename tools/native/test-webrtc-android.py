#!/usr/bin/env python3
"""Run the isolated native RTC journey and verify its post-DTLS Noise capture."""
import argparse
import concurrent.futures
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import importlib.util
import shlex
import struct
import subprocess
import tempfile
from urllib.parse import urlparse
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "cn.edu.ustc.tokenworks"
ADB = ROOT / ".local/android-sdk/platform-tools/adb"
FIXTURE = "rtc-host-fixture.json"


def command(args, *, input=None, timeout=120):
    return subprocess.run([str(arg) for arg in args], input=input, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=True, timeout=timeout).stdout


def checked_sdk():
    source = json.loads((ROOT / "native/webrtc/source.lock.json").read_text())
    aar = ROOT / "android/app/build/generated/webrtc/tokenworks-webrtc.aar"
    proof = json.loads((aar.parent / "build-proof.json").read_text())
    if hashlib.sha256(aar.read_bytes()).hexdigest() != proof["aarSha256"]:
        raise ValueError("The bounded source-built RTC SDK proof does not match its AAR")
    with zipfile.ZipFile(aar) as archive:
        metadata = json.loads(archive.read("META-INF/tokenworks-rtc-build.json"))
    if metadata["sourceCommit"] != source["sourceCommit"] or metadata["patchSha256"] != source["patchSha256"] or not metadata["dataOnly"]:
        raise ValueError("A source-pinned data-only SDK, not the original prebuilt binary, is required")
    return source, proof, metadata["abis"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--exclusive-device-slot", required=True, action="store_true",
                        help="Required caller attestation: obtain the parent's exclusive device slot first")
    parser.add_argument("--route", choices=["p2p", "turn"], required=True)
    parser.add_argument("--rtc-config-file", type=Path, required=True)
    parser.add_argument("--rtc-interface", required=True)
    parser.add_argument("--node", default="node")
    parser.add_argument("--app-apk", type=Path, default=ROOT / "android/app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("This isolated proof runner only accepts an explicitly owned emulator")
    address = ipaddress.ip_address(args.rtc_interface)
    if address.is_unspecified or address.is_loopback or address.is_multicast:
        raise ValueError("An exact declared local interface is required")
    config = args.rtc_config_file.resolve()
    if not config.is_file() or config.stat().st_uid != os.getuid() or config.stat().st_mode & 0o077:
        raise ValueError("The operator RTC configuration must be a private owner-only file")
    sdk, sdk_proof, expected_abis = checked_sdk()
    loader = importlib.util.spec_from_file_location("tokenworks_rtc_builder", ROOT / "tools/native/build-webrtc.py")
    builder = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(builder)
    apk_facts = {}
    with zipfile.ZipFile(args.app_apk) as apk, args.app_apk.open("rb") as raw_apk:
        for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
            info = apk.getinfo(f"lib/{abi}/libjingle_peerconnection_so.so")
            apk_facts[abi] = builder.elf_facts(apk.read(info), abi)
            if apk_facts[abi]["buildId"] != expected_abis[abi]["buildId"]:
                raise ValueError("The APK does not contain the source-built bounded RTC SDK")
            raw_apk.seek(info.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", raw_apk.read(4))
            if info.compress_type != zipfile.ZIP_STORED or (info.header_offset + 30 + name_size + extra_size) % 16384:
                raise ValueError("The actual APK native ZIP entries are not 16KiB aligned")
    adb = [ADB, "-s", args.serial]
    if command([*adb, "shell", "getprop", "ro.kernel.qemu"]).strip() != b"1":
        raise ValueError("The selected device is not an emulator")
    if command([*adb, "shell", "getprop", "ro.build.version.sdk"]).strip() != b"26":
        raise ValueError("This proof explicitly requires the owned API26 runtime")
    evidence_root = ROOT / ".local/rtc-android"
    evidence_root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix=args.route + "-native-", dir=evidence_root))
    evidence.chmod(0o700)
    stderr = (evidence / "host.stderr.log").open("wb")
    os.chmod(stderr.name, 0o600)
    host = subprocess.Popen([args.node, "--import", "tsx", "tools/dev/native-test-host.ts",
        "--rtc-config-file", str(config), "--rtc-interface", args.rtc_interface], cwd=ROOT,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, text=True, encoding="utf-8")
    reader = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    port = None
    staged = False
    try:
        def ready():
            for index, line in enumerate(host.stdout):
                if line.startswith("NATIVE_TEST_FIXTURE="):
                    return Path(line.strip().split("=", 1)[1])
                if index >= 128 or len(line) > 8192:
                    raise ValueError("Unexpected isolated fixture startup output")
            raise RuntimeError("The isolated Host exited before its private descriptor")
        fixture_path = reader.submit(ready).result(timeout=90)
        if not fixture_path.is_file() or fixture_path.stat().st_size > 65536 or fixture_path.stat().st_mode & 0o077:
            raise ValueError("The isolated Host descriptor is not a bounded private file")
        data = fixture_path.read_bytes()
        fixture = json.loads(data)
        if not fixture["rtc"]["enabled"] or fixture["rtc"]["interfaceAddress"] != args.rtc_interface:
            raise ValueError("The Host did not enable the exact declared RTC interface")
        origin = urlparse(fixture["pairing"]["origin"])
        if origin.scheme != "https" or origin.hostname != "localhost" or not origin.port:
            raise ValueError("Only the isolated pinned localhost TLS control endpoint is allowed")
        command([*adb, "install", "-r", args.app_apk], timeout=180)
        command([*adb, "install", "-r", args.test_apk], timeout=180)
        port = origin.port
        command([*adb, "reverse", f"tcp:{port}", f"tcp:{port}"])
        staging_command = f"run-as {shlex.quote(PACKAGE)} sh -c " + shlex.quote(f"test ! -e files/{FIXTURE} && umask 077 && cat > files/{FIXTURE}")
        command([*adb, "shell", staging_command], input=data)
        staged = True
        output = command([*adb, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", PACKAGE + ".RtcNativeCarrierTest", "-e", "rtcHostFixture", FIXTURE,
            "-e", "rtcRoute", args.route, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], timeout=300)
        (evidence / "instrumentation.log").write_bytes(output)
        if b"OK (1 test)" not in output or b"FAILURES!!!" in output or b"INSTRUMENTATION_FAILED" in output:
            raise AssertionError("The real Android native RTC journey did not pass; inspect the private instrumentation log")
        if b"rtc_selected_candidates=" not in output:
            raise AssertionError("Android did not report its actual selected candidate facts")
        capture = Path(fixture["rtc"]["capturePath"]).read_bytes()
        routes = [json.loads(line) for line in Path(fixture["rtc"]["selectedRoutesPath"]).read_text().splitlines()]
        if not routes or any(route["routeKind"] != args.route for route in routes):
            raise AssertionError("Actual selected Host candidate facts do not match the explicit route")
        if args.route == "turn":
            if any(route["localCandidateType"] != "relay" for route in routes):
                raise AssertionError("TURN was not proved by the actual selected relay candidate")
        elif any(route["localCandidateType"] == "relay" or route["remoteCandidateType"] == "relay" for route in routes):
            raise AssertionError("The P2P proof unexpectedly selected TURN")
        if len(capture) < 2 * 400003 or b"tokenworks-noise" not in capture:
            raise AssertionError("The post-DTLS capture does not cover the actual multi-record file transfers")
        for plaintext in (b"REMOTE_APPROVAL_ALLOW", b"approval-allow.txt", b"rtc-large-native.bin",
                          bytes((index * 37 + 11) & 255 for index in range(64))):
            if plaintext in capture:
                raise AssertionError("Native content was visible after DTLS decryption instead of remaining Noise encrypted")
        (evidence / "post-dtls-noise.bin").write_bytes(capture)
        (evidence / "selected-routes.json").write_text(json.dumps(routes, indent=2) + "\n")
        summary = {"route": args.route, "api": 26, "sdkVersion": sdk["version"],
                   "sdkAarSha256": sdk_proof["aarSha256"], "captureBytes": len(capture),
                   "sdkPatchSha256": sdk["patchSha256"], "apkNativeElf": apk_facts, "apkZip16KiBAligned": True,
                   "authorityMode": "independent-local-operator-pair",
                   "captureSha256": hashlib.sha256(capture).hexdigest(), "selectedRoutes": routes,
                   "nativeUnaryMuxFileApprovalConcurrentConsumers": True, "noNativePlaintextAfterDtls": True}
    finally:
        try:
            try:
                if staged:
                    command([*adb, "shell", "run-as", PACKAGE, "rm", "-f", "files/" + FIXTURE])
            finally:
                if port is not None:
                    command([*adb, "reverse", "--remove", f"tcp:{port}"])
        finally:
            try:
                if host.poll() is None:
                    try:
                        host.stdin.write("stop\n"); host.stdin.flush()
                        host.wait(timeout=20)
                    except (BrokenPipeError, subprocess.TimeoutExpired):
                        host.terminate()
                        try: host.wait(timeout=5)
                        except subprocess.TimeoutExpired: host.kill(); host.wait()
            finally:
                reader.shutdown(wait=True)
                stderr.close()
    (evidence / "proof.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("RTC_ANDROID_PROOF=" + str(evidence / "proof.json"))


if __name__ == "__main__":
    main()
