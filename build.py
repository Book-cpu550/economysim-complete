#!/usr/bin/env python3
"""Build/test a checksum-pinned NIM patch of the v1.0.1 release. Requires JDK 25+."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / ".cache"
BUILD = ROOT / "build"
DIST = ROOT / "dist"
ARTIFACTS = {
    "release.jar": (
        "https://github.com/Book-cpu550/economysim-complete/releases/download/v1.0.1/"
        "economysim-complete-1.0.1%2Bmc26.2.jar",
        "f48ad17c6718af17785e72e8909601661efc0fae3633a17122317012ab33bdfc"),
    "asm.jar": ("https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.9/asm-9.9.jar",
                "03d99a74ad1ee5c71334ef67437f4ef4fe3488caa7c96d8645abc73c8e2017d4"),
    "asm-tree.jar": ("https://repo.maven.apache.org/maven2/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.jar",
                     "42178f3775c9c63f9e5e1446747d29b4eca4d91bd6e75e5c43cfa372a47d38c6"),
    "asm-analysis.jar": ("https://repo.maven.apache.org/maven2/org/ow2/asm/asm-analysis/9.9/asm-analysis-9.9.jar",
                         "6a15d28e8bd29ba4fd5bca4baf9b50e8fba2d7b51fbf78cfa0c875a7214c678b"),
    "gson.jar": ("https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar",
                 "57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b"),
}


def run(*args, env=None):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, env=env)


def main():
    if sys.argv[1:] not in ([], ["--test"]):
        raise SystemExit("Usage: python3 build.py [--test]")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    if not java or not javac:
        raise SystemExit("Install JDK 25+ and set JAVA_HOME (a Java runtime alone is insufficient).")
    CACHE.mkdir(exist_ok=True)
    for name, (url, expected) in ARTIFACTS.items():
        path = CACHE / name
        if not path.exists():
            print(f"Downloading {name}", flush=True)
            # curl inherits the managed environment's proxy and CA configuration.
            run("curl", "--fail", "--location", "--silent", "--show-error", "--max-time", "120", url, "--output", path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Checksum mismatch: {path}. Remove it and retry; no output was patched.")
    if BUILD.exists():
        shutil.rmtree(BUILD)
    for directory in (BUILD / "stubs", BUILD / "classes", BUILD / "tools", BUILD / "tests", DIST):
        directory.mkdir(parents=True, exist_ok=True)
    deps = os.pathsep.join(str(CACHE / name) for name in ("asm.jar", "asm-tree.jar", "asm-analysis.jar", "gson.jar"))
    run(javac, "--release", "25", "-d", BUILD / "stubs",
        *sorted((ROOT / "src/compileOnly/java").rglob("*.java")))
    runtime_cp = os.pathsep.join((str(CACHE / "gson.jar"), str(BUILD / "stubs")))
    run(javac, "--release", "25", "-cp", runtime_cp, "-d", BUILD / "classes",
        *sorted((ROOT / "src/main/java").rglob("*.java")))
    run(javac, "--release", "25", "-cp", deps, "-d", BUILD / "tools", ROOT / "tools/PatchRelease.java")
    output = DIST / "economysim-complete-1.0.1-nim+mc26.2.jar"
    run(java, "-cp", str(BUILD / "tools") + os.pathsep + deps, "PatchRelease",
        CACHE / "release.jar", output, BUILD / "classes")
    if "--test" in sys.argv:
        test_cp = os.pathsep.join((str(BUILD / "classes"), runtime_cp))
        run(javac, "--release", "25", "--add-modules", "jdk.httpserver", "-cp", test_cp,
            "-d", BUILD / "tests", *sorted((ROOT / "src/test/java").rglob("*.java")))
        run(java, "-Xverify:all", "--add-modules", "jdk.httpserver", "-Deconomysim.test.config=" + str(BUILD / "test-config"),
            "-cp", str(BUILD / "tests") + os.pathsep + test_cp, "com.booke.nim.NimHttpTest",
            env={k: v for k, v in os.environ.items() if k != "NVIDIA_API_KEY"})
        run(sys.executable, ROOT / "tests/check_archive.py", CACHE / "release.jar", output)
    print(f"Built {output}\nSHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
