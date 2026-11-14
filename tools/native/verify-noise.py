#!/usr/bin/env python3
"""Check the generated four-ABI ELF imports against the pinned API26 NDK stubs."""
import os
from pathlib import Path
import re
import subprocess
import sys

ABIS = {
    "arm64-v8a": ("aarch64-linux-android", "ELF64", "AArch64"),
    "armeabi-v7a": ("arm-linux-androideabi", "ELF32", "ARM"),
    "x86": ("i686-linux-android", "ELF32", "Intel 80386"),
    "x86_64": ("x86_64-linux-android", "ELF64", "Advanced Micro Devices X86-64"),
}
JNI = ("generateStatic", "create", "record", "finish", "rekey", "close")


def check(condition, message):
    if not condition:
        raise SystemExit(message)


def verify(directory, toolchain):
    readelf = toolchain / "bin" / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")

    def elf(path, option):
        return subprocess.check_output([str(readelf), option, str(path)], text=True)

    def symbols(path):
        defined, required = set(), set()
        for line in elf(path, "--dyn-syms").splitlines():
            fields = line.split()
            if len(fields) >= 8 and fields[0].rstrip(":").isdigit():
                name = fields[7].split("@")[0]
                if fields[6] == "UND":
                    if fields[4] != "WEAK": required.add(name)
                else:
                    defined.add(name)
        return defined, required

    for abi, (target, elf_class, machine) in ABIS.items():
        file = directory / abi / "libtokenworks_noise.so"
        check(file.is_file(), f"Missing packaged ABI: {abi}")
        header = elf(file, "--file-header")
        check(f"Class:                             {elf_class}" in header and re.search(r"Machine:\s+" + re.escape(machine), header), f"Wrong ABI: {abi}")
        loads = [line.split() for line in elf(file, "--program-headers").splitlines() if line.strip().startswith("LOAD ")]
        check(loads and all(int(fields[-1], 16) >= 16384 for fields in loads), f"ELF not 16KiB aligned: {abi}")
        exports, imports = symbols(file)
        check(all("Java_cn_edu_ustc_tokenworks_E2eNative_" + method in exports for method in JNI), f"Missing JNI API: {abi}")
        check(not any(name.startswith("napi_") for name in imports | exports), f"Node binding leaked into Android: {abi}")
        needed = re.findall(r"Shared library: \[(.*?)\]", elf(file, "--dynamic"))
        available = set()
        for library in needed:
            stub = toolchain / "sysroot/usr/lib" / target / "26" / library
            check(stub.is_file(), f"Non-API26 native dependency: {abi}/{library}")
            available |= symbols(stub)[0]
        check(not imports - available, f"Non-API26 native imports: {abi}: {sorted(imports - available)}")
        print(f"{abi}: API26 imports, JNI exports, {elf_class}, 16KiB LOAD alignment verified")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
