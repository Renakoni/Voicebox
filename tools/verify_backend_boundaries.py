"""Check the host APK and independently built backend candidates. Uses only Python's standard library."""
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
MODEL_CLASSES = ("Ldev/renakoni/voicebox/model/", "Ldev/ming/local/", "Lai/onnxruntime/", "Lcom/qwen3/android/")
PARENT_CLASSES = ("Ldev/renakoni/voicebox/core/", "Ldev/renakoni/voicebox/bridge/", "Lkotlin/")
GPT_LIBS = {"libc++_shared.so", "libvoicebox_japanese.so", "libnative_decoder_loop.so",
            "libonnxruntime.so", "libonnxruntime4j_jni.so"}
MING_LIBS = {"libonnxruntime.so", "libming-synthesize.so", "libming-reference.so",
             "libming-ort.so", "libming-gpu-probe.so", "libming-mmla-neon.so", "libming-mmla-bf16.so",
             "libming-htp.so", "qnn/libonnxruntime-qnn.so", "qnn/libQnnHtp.so",
             "qnn/libQnnHtpPrepare.so", "qnn/libQnnSystem.so"}
MING_NOTICES = {"Qualcomm-LICENSE.pdf", "Qualcomm-NOTICE.txt", "ONNXRuntime-LICENSE.txt",
                "ONNXRuntime-NOTICE.txt", "Khronos-OpenCL-LICENSE.txt", "Ming-THIRD-PARTY.txt",
                "Voicebox-LICENSE.txt", "pocketfft-LICENSE.txt"}
QWEN_LIBS = {"cpu/libqwen3_jni.so", "accelerated/libqwen3_jni.so", "libqwen3_probe.so", "libqwen3_checkpoint.so"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_sources():
    dependencies = {"app": {"core"}, "core": {"bridge-api"}, "bridge-api": set(),
                    "model/gptsovits": {"core", "bridge-api"}, "model/ming": {"core", "bridge-api"},
                    "model/qwen3": {"core", "bridge-api"}}
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
    abi = spec["abi"]
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    with ZipFile(BytesIO(data)) as archive:
        sizes = {entry.filename: entry.file_size for entry in archive.infolist()}
        require(len(sizes) == len(archive.infolist()) and sizes == spec["files"], f"{backend}: file inventory differs")
        dsp = set()
        if backend == "gptsovits":
            libs = GPT_LIBS
            audit = json.loads((ANDROID / "model/gptsovits/bridge-assets.json").read_bytes())
            assets = {"assets/" + path for path in audit}
        elif backend == "qwen3":
            require(abi == "arm64-v8a", "Expected Qwen ARM64 package")
            libs = QWEN_LIBS
            dsp = {"accelerated/libggml-htp-v75.so"}
            assets = {"assets/qwen-vk2-1124406273-2150531084.bin",
                      "assets/checkpoint-layout.json", "assets/checkpoint-metadata.bin"} | {
                f"licenses/{name}-LICENSE.txt" for name in ("qwentts", "ggml", "Qwen3-TTS", "VoiceBox")}
        else:
            require(abi == "arm64-v8a", "Expected Ming ARM64 package")
            stubs = {name for name in sizes if re.fullmatch(r"qnn/libQnnHtpV[0-9]+Stub\.so", name)}
            require(stubs, "Ming package requires HTP Stub/Skel pairs")
            libs = MING_LIBS | stubs
            dsp = {name.replace("Stub.so", "Skel.so") for name in stubs}
            assets = {"provider-probe.onnx"} | {f"licenses/{name}" for name in MING_NOTICES}
        expected = libs | assets | dsp | {"bridge.json", spec["code"]}
        require(set(sizes) == expected, f"{backend}: unexpected or missing payloads {set(sizes) ^ expected}")
        for name in libs:
            with archive.open(name) as library:
                elf = library.read(20)
            require(elf[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", elf, 18)[0] == machine,
                    f"{name}: wrong native ABI")
        with ZipFile(BytesIO(archive.read(spec["code"]))) as jar:
            require(all(re.fullmatch(r"classes\d*\.dex", name) for name in jar.namelist()),
                    f"{backend}: unexpected DEX JAR member")
            classes = definitions(jar)
        require("L" + spec["entry"].replace(".", "/") + ";" in classes, f"{backend}: missing entry")
        owned = {"ming": ("Ldev/renakoni/voicebox/model/ming/", "Ldev/ming/local/"),
                 "gptsovits": ("Ldev/renakoni/voicebox/model/gptsovits/", "Lai/onnxruntime/"),
                 "qwen3": ("Ldev/renakoni/voicebox/model/qwen3/", "Lcom/qwen3/android/")}
        forbidden = PARENT_CLASSES + tuple(prefix for owner, prefixes in owned.items() if owner != backend for prefix in prefixes)
        require(not any(name.startswith(forbidden) for name in classes), f"{backend}: duplicate or foreign classes")
    return dict(abi=abi, bytes=len(data), classes=len(classes), assets=len(assets))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "gpt", "ming", "qwen3"):
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
        require(not (native & ((GPT_LIBS - {"libc++_shared.so"}) | {Path(n).name for n in QWEN_LIBS} | {"libggml-htp-v75.so"})
                     or any(n.startswith("libming-") for n in native)),
                "Host includes backend native code")
        require(not any(entry.filename.startswith("assets/backend-packages/") for entry in members),
                "Host bundles bridge packages")
        host_abis = {n.split("/")[1] for n in app.namelist() if n.startswith("lib/") and n.endswith(".so")}
        report = {"host_classes": len(host)}
        weights = json.loads(app.read("assets/qwen3-models.json"))
        require(set(weights) == {"qwen-talker-0.6b-base-Q4_K_M.gguf", "qwen-tokenizer-12hz-F32.gguf"},
                "Qwen requires exactly the Q4 Talker and F32 codec")
        for backend, path in (("gptsovits", args.gpt), ("ming", args.ming), ("qwen3", args.qwen3)):
            spec = json.loads(app.read(f"assets/bridges/{backend}.json"))
            require(spec["id"] == backend, "Backend catalog misidentified")
            data = path.read_bytes()
            parts = spec["parts"]
            require(len(parts) == 1 and parts[0]["bytes"] == len(data), f"{backend}: catalog package size differs")
            require(hashlib.sha256(data).hexdigest() == parts[0]["sha256"], f"{backend}: catalog package checksum differs")
            url = parts[0]["url"]
            require(url.startswith("https://") and url.rsplit("/", 1)[-1] == path.name,
                    f"{backend}: catalog URL differs")
            require(host_abis == {spec["abi"]}, f"{backend}: APK and bridge ABIs differ")
            require(not any(Path(entry.filename).name == path.name or
                            (entry.file_size == len(data) and app.read(entry) == data) for entry in members),
                    f"{backend}: downloadable package is bundled in the APK")
            report[backend] = verify_package(spec, data)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
