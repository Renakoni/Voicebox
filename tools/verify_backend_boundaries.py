"""Check the host APK and both independently built backend candidates. Uses only Python's standard library."""
import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import struct
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "Android"
MODEL_CLASSES = ("Ldev/renakoni/voicebox/model/", "Ldev/ming/local/", "Lai/onnxruntime/")
PARENT_CLASSES = ("Ldev/renakoni/voicebox/core/", "Ldev/renakoni/voicebox/bridge/", "Lkotlin/")
GPT_LIBS = {"libc++_shared.so", "libvoicebox_japanese.so", "libnative_decoder_loop.so",
            "libonnxruntime.so", "libonnxruntime4j_jni.so"}
MING_LIBS = {"libonnxruntime.so", "libming-synthesize.so", "libming-reference.so"}
MING_PROBE_SHA = "73e643d2754466ba6a8760c29e10cd04e75dadcb088aed2807da9ba9419e4611"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check_sources():
    dependencies = {"app": {"core"}, "core": {"bridge-api"}, "bridge-api": set(),
                    "model/gptsovits": {"core", "bridge-api"}, "model/ming": {"core", "bridge-api"}}
    for name, allowed in dependencies.items():
        module = ANDROID / name
        gradle = (module / "build.gradle.kts").read_text(encoding="utf-8")
        projects = set(re.findall(r'project\(\s*":([^"]+)"\s*\)', gradle))
        require(not projects - allowed, f"{name} links disallowed projects: {projects - allowed}")
        if name.startswith("model/"):
            continue
        for path in (module / "src/main").rglob("*"):
            if path.suffix in {".kt", ".java"}:
                code = path.read_text(encoding="utf-8")
                require(not any(prefix[1:].replace("/", ".") in code for prefix in MODEL_CLASSES),
                        f"{path} references a backend implementation")


def dex_classes(data):
    # class_defs.class_idx -> type_ids.descriptor_idx -> string_ids.string_data_off.
    def u32(start):
        return struct.unpack_from("<I", data, start)[0]

    require(data.startswith(b"dex\n"), "Invalid DEX header")
    for i in range(u32(96)):
        type_id = u32(u32(100) + i * 32)
        string_id = u32(u32(68) + type_id * 4)
        start = u32(u32(60) + string_id * 4)
        while data[start] & 0x80:
            start += 1
        start += 1
        yield data[start:data.index(0, start)].decode("utf-8", errors="replace")


def definitions(archive):
    dex = [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
    require(dex, "Missing DEX")
    return {name for n in dex for name in dex_classes(archive.read(n))}


def verify_package(spec, data):
    backend = spec["id"]
    require(spec["format"] == 2 and spec["contract"] == 1 and spec["version"] and spec["displayName"],
            f"{backend}: invalid catalog")
    require(re.fullmatch("[a-f0-9]{40}", spec["sourceCommit"]) and
            re.fullmatch("[a-f0-9]{64}", spec["adapterSourceSha256"]), f"{backend}: missing source identity")
    part, = spec["parts"]
    digest = sha(data)
    require(len(data) == part["bytes"] and digest == part["sha256"], f"{backend}: package hash or size differs")
    revision = digest if backend == "gptsovits" else sha(digest.encode("ascii"))
    require(spec["revision"] == revision, f"{backend}: revision differs")
    abi = spec["abi"]
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    with ZipFile(BytesIO(data)) as archive:
        sizes = {entry.filename: entry.file_size for entry in archive.infolist()}
        require(len(sizes) == len(archive.infolist()) and sizes == spec["files"], f"{backend}: file inventory differs")
        manifest = json.loads(archive.read("bridge.json"))
        for key in ("id", "entry", "code", "contract", "abi", "adapterSourceSha256", "sourceCommit"):
            require(manifest[key] == spec[key], f"{backend}: manifest mismatch for {key}")
        if backend == "gptsovits":
            libs = GPT_LIBS
            audit = json.loads((ANDROID / "model/gptsovits/bridge-assets.json").read_bytes())
            assets = {"assets/" + path: digest for path, digest in audit.items()}
        else:
            libs = MING_LIBS | ({"libming-mmla-neon.so", "libming-mmla-bf16.so"} if abi == "arm64-v8a" else set())
            assets = {"webgpu-probe.onnx": MING_PROBE_SHA}
        expected = libs | set(assets) | {"bridge.json", spec["code"]}
        require(set(sizes) == expected, f"{backend}: unexpected or missing payloads {set(sizes) ^ expected}")
        for name, expected_hash in assets.items():
            require(sha(archive.read(name)) == expected_hash, f"{backend}: unreviewed asset {name}")
        for name in libs:
            elf = archive.read(name)
            require(elf[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", elf, 18)[0] == machine,
                    f"{name}: wrong native ABI")
        with ZipFile(BytesIO(archive.read(spec["code"]))) as jar:
            require(all(re.fullmatch(r"classes\d*\.dex", name) for name in jar.namelist()),
                    f"{backend}: unexpected DEX JAR member")
            classes = definitions(jar)
        require("L" + spec["entry"].replace(".", "/") + ";" in classes, f"{backend}: missing entry")
        forbidden = PARENT_CLASSES + (("Ldev/renakoni/voicebox/model/ming/", "Ldev/ming/local/") if backend == "gptsovits"
                                      else ("Ldev/renakoni/voicebox/model/gptsovits/", "Lai/onnxruntime/"))
        require(not any(name.startswith(forbidden) for name in classes), f"{backend}: duplicate or foreign classes")
    return dict(abi=abi, sha256=digest, bytes=len(data), classes=len(classes), assets=len(assets))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "gpt", "ming"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--mapping", type=Path, help="R8 mapping.txt from this APK's build")
    args = parser.parse_args()
    check_sources()
    with ZipFile(args.apk) as app:
        members = app.infolist()
        require(len(members) == len(set(app.namelist())), "APK contains duplicate paths")
        host = definitions(app)
        require(not any(name.startswith(MODEL_CLASSES) for name in host), "Host defines backend classes")
        if args.mapping:
            for line in args.mapping.read_text(encoding="utf-8").splitlines():
                if line.endswith(":") and " -> " in line:
                    original = "L" + line.split(" -> ", 1)[0].replace(".", "/") + ";"
                    require(not original.startswith(MODEL_CLASSES), "Host R8 mapped a backend class: " + original)
        native = {Path(n).name for n in app.namelist() if n.startswith("lib/")}
        require(not (native & (GPT_LIBS - {"libc++_shared.so"}) or any(n.startswith("libming-") for n in native)),
                "Host includes backend native code")
        report = {"host_classes": len(host)}
        for backend, path in (("gptsovits", args.gpt), ("ming", args.ming)):
            spec = json.loads(app.read(f"assets/bridges/{backend}.json"))
            require(spec["id"] == backend, "Backend catalog misidentified")
            data = path.read_bytes()
            url = spec["parts"][0]["url"]
            if backend == "gptsovits":
                require(url.startswith("asset://"), "GPT is not bundled")
                host_abis = {n.split("/")[1] for n in app.namelist() if n.startswith("lib/") and n.endswith(".so")}
                require(host_abis == {spec["abi"]}, "Host and bundled GPT native ABIs differ")
                bundled = [n for n in app.namelist() if n.startswith("assets/backend-packages/")]
                require(bundled == ["assets/" + url.removeprefix("asset://")] and app.read(bundled[0]) == data,
                        "Bundled and standalone GPT candidates differ")
            else:
                require(url.startswith("https://") and url.rsplit("/", 1)[-1] == path.name, "Ming catalog URL differs")
                require(not any(Path(entry.filename).name == path.name or
                                (entry.file_size == len(data) and app.read(entry) == data) for entry in members),
                        "Downloadable Ming package is bundled in the APK")
            report[backend] = verify_package(spec, data)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
