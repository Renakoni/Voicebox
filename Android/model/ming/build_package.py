"""Build a downloadable Ming runtime from the existing ming-android task implementation.

No Ming Java, native libraries or weights are added to the application's source sets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = '1'


def run(*command):
    subprocess.run([str(value).replace('\\', '/') for value in command], check=True)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def add_file(archive, path, name):
    entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    entry.compress_type = archive.compression
    with path.open('rb') as source, archive.open(entry, 'w') as target:
        shutil.copyfileobj(source, target)


def adapter_source_hash():
    digest = hashlib.sha256()
    inputs = [ROOT / name for name in ('bridge.cpp', 'CMakeLists.txt', 'Native.java', 'build_package.py', 'build.gradle.kts', 'webgpu-probe.onnx')]
    for path in sorted(inputs + list((ROOT / 'src/main/java').rglob('*.kt'))):
        digest.update(path.relative_to(ROOT).as_posix().encode() + b'\0')
        data = path.read_bytes()
        digest.update(data if path.suffix == '.onnx' else data.replace(b'\r\n', b'\n'))
    return digest.hexdigest()


def adapt_synthesis(source):
    start = source.index('        ProcessBuilder builder=')
    end = source.index('\n    }', start)
    source = source[:start] + '        Native.process(command,extra,log);' + source[end:]
    old = 'public static void main(String[] args) {\n        try {'
    assert source.count(old) == 1, 'Review changed Ming task entrypoint before packaging'
    source = source.replace(old, 'public static void run(String[] args) throws Exception {')
    old = '        } catch(Throwable error) {error.printStackTrace();System.exit(1);}\n'
    assert source.count(old) == 1
    return source.replace(old, '')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='ming-android checkout')
    parser.add_argument('--ort-library', type=Path, required=True, help='Matching Android ONNX Runtime shared library')
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--ort-include', type=Path, required=True)
    parser.add_argument('--pocketfft-include', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], default='arm64-v8a')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--release-url', default=f'https://github.com/Renakoni/Voicebox/releases/download/ming-runtime-v{VERSION}')
    parser.add_argument('--catalog', type=Path, help='Also write the pinned app asset catalog here')
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    build, runtime = output / 'build' / args.abi, output / 'runtime'
    build.mkdir(parents=True, exist_ok=True)
    runtime.mkdir(exist_ok=True)
    shutil.copyfile(args.ort_library, runtime / 'libonnxruntime.so')
    sdk = args.sdk.resolve()
    cmake = sdk / 'cmake/3.22.1/bin/cmake.exe'
    run(cmake, '-S', ROOT, '-B', build / 'native', '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={sdk}/cmake/3.22.1/bin/ninja.exe',
        f'-DCMAKE_TOOLCHAIN_FILE={sdk}/ndk/28.2.13676358/build/cmake/android.toolchain.cmake',
        f'-DANDROID_ABI={args.abi}', '-DANDROID_PLATFORM=android-30', '-DANDROID_STL=c++_static',
        f'-DMING_SOURCE={source}', f'-DORT_INCLUDE={args.ort_include.resolve()}',
        f'-DORT_LIBRARY={runtime}/libonnxruntime.so', f'-DPOCKETFFT_INCLUDE={args.pocketfft_include.resolve()}',
        '-DCMAKE_BUILD_TYPE=Release')
    run(cmake, '--build', build / 'native', '--parallel', '2')
    for library in (build / 'native').glob('*.so'):
        shutil.copyfile(library, runtime / library.name)
    with tempfile.TemporaryDirectory(dir=build) as temporary:
        java, classes, dex = (Path(temporary) / name for name in ('java', 'classes', 'dex'))
        for directory in (java, classes, dex):
            directory.mkdir()
        for name in ['Tokenizer', 'TaskRequest', 'TextSegments', 'Synthesis']:
            code = (source / f'android/{name}.java').read_text(encoding='utf-8')
            if name == 'Synthesis':
                code = adapt_synthesis(code)
            (java / f'{name}.java').write_text(code, encoding='utf-8')
        shutil.copyfile(ROOT / 'Native.java', java / 'Native.java')
        android_jar = sdk / 'platforms/android-36/android.jar'
        run('javac', '--release', '11', '-encoding', 'UTF-8', '-cp', android_jar,
            '-d', classes, *sorted(java.glob('*.java')))
        android_root = ROOT.parents[1]
        run(android_root / 'gradlew.bat', '-p', android_root, ':model:ming:bundleReleaseAar')
        adapter = ROOT / 'build/intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar'
        run(sdk / 'build-tools/37.0.0/d8.bat', '--min-api', '30', '--lib', android_jar,
            '--output', dex, adapter, *sorted(classes.rglob('*.class')))
        with zipfile.ZipFile(runtime / 'tasks.jar', 'w') as archive:
            add_file(archive, dex / 'classes.dex', 'classes.dex')
    # Explicit code allowlist: never collect model weights, graphs or voice resources.
    manifest = {'contract': 1, 'id': 'ming', 'entry': 'dev.renakoni.voicebox.model.ming.MingEntry',
                'code': 'tasks.jar', 'abi': args.abi, 'adapterSourceSha256': adapter_source_hash(), 'sourceCommit': subprocess.check_output(
                    ['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()}
    (runtime / 'bridge.json').write_text(json.dumps(manifest), encoding='utf-8')
    shutil.copyfile(ROOT / 'webgpu-probe.onnx', runtime / 'webgpu-probe.onnx')
    names = ['webgpu-probe.onnx', 'bridge.json', 'tasks.jar', 'libonnxruntime.so', 'libming-synthesize.so', 'libming-reference.so']
    if args.abi == 'arm64-v8a':
        names += ['libming-mmla-neon.so', 'libming-mmla-bf16.so']
    files = [(runtime / name, name) for name in names]
    name = f'ming-backend-v{VERSION}-{args.abi}.zip'
    archive_path = output / name
    print(f'Packaging {name}', flush=True)
    with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path, relative in files:
            add_file(archive, path, relative)
    parts = [{'url': args.release_url.rstrip('/') + '/' + name,
              'bytes': archive_path.stat().st_size, 'sha256': sha256(archive_path)}]
    catalog = {**manifest, 'format': 2, 'displayName': 'Ming-TTS 0.5B', 'version': VERSION,
               'revision': parts[0]['sha256'],
               'parts': parts, 'files': {name: path.stat().st_size for path, name in files}}
    payload = json.dumps(catalog, ensure_ascii=False, indent=2) + '\n'
    (output / 'ming-runtime.json').write_text(payload, encoding='utf-8')
    if args.catalog:
        args.catalog.parent.mkdir(parents=True, exist_ok=True)
        args.catalog.write_text(payload, encoding='utf-8')
    print(f'Ready: {output} ({sum(p["bytes"] for p in parts)} download bytes)', flush=True)


if __name__ == '__main__':
    main()
