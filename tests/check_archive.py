"""Check bundle structure and that unrelated entries retain identical bytes."""
import io
import json
import sys
import zipfile

original_path, patched_path = sys.argv[1:]
changed_modules = {
    "economysim-item-rater-1_0_0.jar", "economysim-loan-manager-1.0.1.jar",
    "economysim-predicted-prices-1.3.5.jar", "CLAUDE_ADVISOR_ollama_all_market_items.jar",
}
with zipfile.ZipFile(original_path) as original, zipfile.ZipFile(patched_path) as patched:
    assert patched.testzip() is None
    assert not any(name.startswith("net/fabricmc/") for name in patched.namelist()), "compile-only stub leaked"
    assert "com/booke/nim/NimHttp.class" in patched.namelist()
    metadata = json.loads(patched.read("fabric.mod.json"))
    assert metadata["id"] == "economysim_complete"
    assert metadata["version"] == "1.0.1+nim.1"
    for entry in metadata["jars"]:
        assert entry["file"] in patched.namelist()
    for name in original.namelist():
        if name == "fabric.mod.json":
            continue
        if name.rsplit("/", 1)[-1] not in changed_modules:
            assert patched.read(name) == original.read(name), f"unrelated entry changed: {name}"
            continue
        with zipfile.ZipFile(io.BytesIO(original.read(name))) as before, zipfile.ZipFile(io.BytesIO(patched.read(name))) as after:
            assert after.testzip() is None
            info = json.loads(after.read("fabric.mod.json"))
            assert info["id"] == json.loads(before.read("fabric.mod.json"))["id"]
            assert info["version"] == json.loads(before.read("fabric.mod.json"))["version"] + "+nim.1", "preserve dependency ordering with build metadata"
            for entry in before.namelist():
                if entry.endswith("LocalModelLifecycle.class"):
                    assert entry not in after.namelist()
                elif not entry.endswith(".class") and entry != "fabric.mod.json":
                    assert before.read(entry) == after.read(entry), f"resource changed: {entry}"
            for entry in after.namelist():
                data = after.read(entry)
                assert b"localhost:11434" not in data, f"local endpoint remains: {entry}"
                assert b"ANTHROPIC_API_KEY" not in data, f"old key environment remains: {entry}"
                if entry.endswith(".class"):
                    expected_version = 69 if entry.endswith("/AdvisorConfig.class") else int.from_bytes(before.read(entry)[6:8], "big")
                    assert int.from_bytes(data[6:8], "big") == expected_version <= 69, "Java 25 compatibility and original class versions"
            if "entrypoints" in info:
                assert "com.example.economyadvisor.LocalModelLifecycle" not in info["entrypoints"]["client"]
            if info["id"] == "economysim_advisor":
                commands = after.read("com/example/economyadvisor/AdvisorEntry.class")
                for command in (b"key", b"paste", b"clear", b"model", b"status"):
                    assert command in commands, f"existing advisor command missing: {command}"
    print("PASS: archive integrity, dependencies, preserved resources and unrelated modules, Java 25, no Ollama lifecycle/endpoint.")
