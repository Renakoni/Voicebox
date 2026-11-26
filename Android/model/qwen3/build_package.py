"""Build the Q4 VoiceBox bridge using the 0.2.0 qwen3-android experiment.

Apply optional-hexagon.patch in a clean qwen3-android source checkout, run its
tools/build_android_htp.sh and pass the resulting jni/arm64-v8a directory with --accelerated-dir.
VoiceBoxImports contains the three files for offline installation or release hosting.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
from checkpoint_layout import checkpoint_assets

ROOT = Path(__file__).resolve().parent
ANDROID = ROOT.parents[1]
ABI = "arm64-v8a"
VERSION = "5"
HTP_ARCHES = (73, 75, 79, 81, 85)
UPSTREAM = "2edb35f87b709a070b2d5379f77e5c52a1daa585"
WEIGHTS = ("qwen-talker-0.6b-base-Q4_K_M.gguf", "qwen-tokenizer-12hz-F32.gguf")


def run(*command):
    subprocess.run([str(value).replace("\\", "/") for value in command], check=True)


def json_bytes(value):
    return (json.dumps(value, indent=2) + "\n").encode("utf-8")


def write_zip(path, files):
    with ZipFile(path, "w") as archive:
        for name, value in sorted(files.items()):
            entry = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            archive.writestr(entry, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--accelerated-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"), type=Path)
    parser.add_argument("--release-url", default=f"https://github.com/Renakoni/Voicebox/releases/download/qwen3-runtime-v{VERSION}")
    parser.add_argument("--models-release-url", default="https://github.com/Renakoni/Voicebox/releases/download/qwen3-models-v1")
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--runtime-only", action="store_true", help="Build the bridge without copying the model downloads")
    args = parser.parse_args()
    source = args.source.resolve()
    sdk = args.sdk.resolve()
    imports = args.output.resolve() / "VoiceBoxImports"
    imports.mkdir(parents=True, exist_ok=True)
    native = source / "third_party/qwentts"
    if UPSTREAM[:12] not in (source / "native/version.h.in").read_text():
        raise ValueError("Review the qwentts.cpp revision used by this experiment")
    build = ROOT / "build/cpu"
    tools = sdk / "cmake/3.22.1/bin"
    ndk = sdk / "ndk/28.2.13676358"
    llvm = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    run(tools / "cmake.exe", "-S", source / "native", "-B", build, "-G", "Ninja",
        f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake", f"-DCMAKE_MAKE_PROGRAM={tools}/ninja.exe",
        f"-DANDROID_ABI={ABI}", "-DANDROID_PLATFORM=android-30", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
        "-DGGML_CPU_ARM_ARCH=armv8-a", "-DGGML_VULKAN=OFF")
    run(tools / "cmake.exe", "--build", build, "--parallel", "5", "--target", "qwen3_jni")
    run(ANDROID / "gradlew.bat", "-p", ANDROID, ":model:qwen3:bundleReleaseAar")
    adapter = ROOT / "build/intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"
    with TemporaryDirectory(dir=ROOT / "build") as temporary:
        temp = Path(temporary)
        run(sdk / "build-tools/37.0.0/d8.bat", "--min-api", "30", "--lib", sdk / "platforms/android-37.0/android.jar",
            "--output", temp, adapter)
        write_zip(temp / "tasks.jar", {"classes.dex": (temp / "classes.dex").read_bytes()})
        run(llvm / "llvm-strip.exe", "--strip-unneeded", build / "libqwen3_jni.so", "-o", temp / "libqwen3_jni.so")
        # Both cores use the same configured synthesis entry point and PCM callbacks.
        shutil.copyfile(source / "native/qwen3_jni.cpp", temp / "qwen3_jni.cpp")
        subprocess.run(["git", "apply", "--no-index", "--ignore-space-change",
                        str(ROOT / "synthesis.patch")], cwd=temp, check=True)
        exports = temp / "exports.map"
        exports.write_text("{ global: Java_com_qwen3_android_Engine_synthesizeConfigured; "
                           "Java_dev_renakoni_voicebox_model_qwen3_checkpoint_QwenCheckpointConverter_convertRows; local: *; };")
        run(llvm / "clang++.exe", "--target=aarch64-linux-android30", "-std=c++17", "-shared", "-fPIC", "-O2",
            "-ffunction-sections", "-fdata-sections", "-static-libstdc++", "-Wl,--gc-sections", "-Wl,--no-undefined",
            "-Wl,-z,max-page-size=16384", f"-Wl,--version-script={exports}", "-I", native / "src",
            "-I", native / "ggml/include", temp / "qwen3_jni.cpp", ROOT / "checkpoint_rows.cpp",
            build / "libqwen3_jni.so", "-llog", "-o", temp / "libqwen3_checkpoint.so")
        files = {name: (temp / name).read_bytes() for name in ("tasks.jar", "libqwen3_checkpoint.so")}
        files["cpu/libqwen3_jni.so"] = (temp / "libqwen3_jni.so").read_bytes()
        gpu = temp / "accelerated.so"
        run(llvm / "llvm-strip.exe", "--strip-unneeded", args.accelerated_dir / "libqwen3_jni.so", "-o", gpu)
        symbols = subprocess.check_output([llvm / "llvm-nm.exe", "--dynamic", "--defined-only", gpu])
        if b"Java_dev_renakoni_voicebox_model_qwen3_QwenDevices_probe" not in symbols.split():
            raise ValueError("Build the accelerated runtime with optional-hexagon.patch")
        files["accelerated/libqwen3_jni.so"] = gpu.read_bytes()
    for arch in HTP_ARCHES:
        name = f"libggml-htp-v{arch}.so"
        data = (args.accelerated_dir / name).read_bytes()
        # ELF32, little endian, EM_HEXAGON; e_flags carries the DSP ISA in its low byte.
        if data[:6] != b"\x7fELF\x01\x01" or int.from_bytes(data[18:20], "little") != 164 or data[36] != int(str(arch), 16):
            raise ValueError(f"Expected a Hexagon v{arch} DSP library in {name}")
        files["accelerated/" + name] = data
    for name, path in {"qwentts": native, "ggml": native / "ggml", "Qwen3-TTS": source.parent / "Qwen3-TTS",
                       "VoiceBox": ANDROID.parent}.items():
        files[f"licenses/{name}-LICENSE.txt"] = (path / "LICENSE").read_bytes()

    release = args.release_url.rstrip("/")
    models_release = args.models_release_url.rstrip("/")
    models = source / "models"
    files.update(checkpoint_assets(source.parent / "origin", models / WEIGHTS[0]))
    pins = {name: digest for digest, name in (line.split() for line in (models / "SHA256SUMS").read_text().splitlines() if line)}
    weights = {}
    for name in WEIGHTS:
        model = models / name
        weights[name] = dict(url=f"{models_release}/{name}", bytes=model.stat().st_size, sha256=pins[name])
        target = imports / name
        if not args.runtime_only and (not target.exists() or not target.samefile(model)):
            shutil.copyfile(model, target)
    weights_catalog = json_bytes(weights)
    (imports.parent / "qwen3-models.json").write_bytes(weights_catalog)
    adapter_source = hashlib.sha256()
    sources = [ROOT / name for name in ("build_package.py", "build.gradle.kts", "checkpoint_layout.py",
                                         "checkpoint_rows.cpp", "synthesis.patch", "optional-hexagon.patch")]
    for path in sorted(sources + list((ROOT / "src").rglob("*.kt")) + list((ROOT / "src").rglob("*.java"))):
        adapter_source.update(path.relative_to(ROOT).as_posix().encode() + b"\0" + path.read_bytes().replace(b"\r\n", b"\n"))
    manifest = dict(contract=1, id="qwen3", entry="dev.renakoni.voicebox.model.qwen3.QwenEntry", code="tasks.jar",
                    abi=ABI, minAppVersionCode=13, sourceCommit=UPSTREAM, adapterSourceSha256=adapter_source.hexdigest())
    files["bridge.json"] = json_bytes(manifest)
    archive = imports / f"qwen3-backend-v{VERSION}-{ABI}.zip"
    write_zip(archive, files)
    with archive.open("rb") as stream:
        revision = hashlib.file_digest(stream, "sha256").hexdigest()
    catalog = dict(manifest, format=2, displayName="Qwen3-TTS-0.6B", version=VERSION, revision=revision,
                   minSdk=31, requiresVulkan12Storage16=True,
                   parts=[dict(url=f"{release}/{archive.name}", bytes=archive.stat().st_size, sha256=revision)],
                   files={name: len(data) for name, data in sorted(files.items())},
                   initialResourcePacks=[dict(id="codec", titleKey="qwen_audio_codec", bytes=weights[WEIGHTS[1]]["bytes"])])
    (imports.parent / "qwen3-runtime.json").write_bytes(json_bytes(catalog))
    if args.catalog:
        args.catalog.write_bytes(json_bytes(catalog))
        (args.catalog.parent.parent / "qwen3-models.json").write_bytes(weights_catalog)
    print(f"Ready: {imports}")


if __name__ == "__main__":
    main()
