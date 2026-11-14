#!/usr/bin/env python3
"""Resolve actual JNI-facade Java method references against Android API metadata."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

CLASSES = ("E2eSession", "E2eSession$Companion", "E2eNative", "E2eStaticKeyPair", "E2eHandshakeBinding")


def verify(classpath, api_file):
    classes = {item.attrib["name"]: item for item in ET.parse(api_file).getroot().findall("class")}

    def available(owner, signature, visited=None):
        visited = set() if visited is None else visited
        if owner in visited:
            return False
        visited.add(owner)
        item = classes.get(owner)
        if item is None or int(item.get("since", "1")) > 26:
            return False
        for method in item.findall("method"):
            if method.get("name") == signature:
                return int(method.get("since", item.get("since", "1"))) <= 26 and int(method.get("removed", "1000")) > 26
        return any(available(parent.attrib["name"], signature, visited) for parent in [*item.findall("extends"), *item.findall("implements")])

    methods = set()
    for name in CLASSES:
        output = subprocess.check_output(["javap", "-classpath", str(classpath), "-v", "-p", "cn.edu.ustc.tokenworks." + name], text=True)
        if "major version: 61" not in output:
            raise SystemExit(f"JNI facade is not Java17 bytecode: {name}")
        for owner, method, descriptor in re.findall(r"// (java/[A-Za-z0-9_/$]+)\.([^: ]+):(\S+)", output):
            if descriptor.startswith("("):
                methods.add((owner, method.replace('"', '') + descriptor))
    unsupported = sorted(f"{owner}.{signature}" for owner, signature in methods if not available(owner, signature))
    if unsupported:
        raise SystemExit("Non-API26 facade methods: " + ", ".join(unsupported))
    print(f"Java17 JNI facade bytecode: {len(methods)} exact Java method references resolve at Android API26")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
