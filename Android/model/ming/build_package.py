"""Build a downloadable Ming runtime from the existing ming-android task implementation.

No Ming Java, native libraries or weights are added to the application's source sets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = '3'
ABI = 'arm64-v8a'
PROBE = ROOT.parent / 'gptsovits/src/main/assets/device-acceleration/probe.onnx'
QNN_LIBRARIES = ('libQnnHtp.so', 'libQnnHtpPrepare.so', 'libQnnSystem.so')
JNI = f'jni/{ABI}/'


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
    inputs = list(ROOT.glob('*.cpp')) + list(ROOT.glob('*.h')) + list(ROOT.glob('*.java')) + [ROOT / name for name in
        ('CMakeLists.txt', 'build_package.py', 'build.gradle.kts')]
    for path in sorted(inputs + list((ROOT / 'src/main/java').rglob('*.kt'))):
        digest.update(path.relative_to(ROOT).as_posix().encode() + b'\0')
        data = path.read_bytes()
        digest.update(data.replace(b'\r\n', b'\n'))
    digest.update(b'provider-probe.onnx\0' + PROBE.read_bytes())
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
    source = source.replace(old, '').replace('"auto","cpu","webgpu"', '"auto","cpu","webgpu","htp"')
    source = source.replace('args.length<=5', 'args.length<=6').replace('[THREADS]"', '[THREADS] [STREAM_DIR]"')
    anchor = '            TaskRequest request=new TaskRequest'
    source = replace_once(source, anchor,
        '            Path stream=args.length==6?new File(args[5]).toPath().toAbsolutePath():null;\n' + anchor)
    source = replace_once(source, 'String threads,int startIndex)', 'String threads,int startIndex,Path stream)')
    for index in (0, 1):
        source = source.replace(f',mode,threads,{index})', f',mode,threads,{index},stream)')
    source = replace_once(source, '        Files.write(work.resolve("blocks.i64"),blocks.array());',
        '''        if (stream!=null) {
            blocks=ByteBuffer.allocate(8*fragments.size()).order(ByteOrder.LITTLE_ENDIAN);
            for (int i=0;i<fragments.size();i++) blocks.putLong(1);
        }
        Files.write(work.resolve("blocks.i64"),blocks.array());''')
    anchor = '''        process(Arrays.asList(bundle.resolve("ming-run").toString(),bundle.resolve("models").toString(),
                work.resolve("000.i64").toString(),work.resolve("unused.wav").toString(),"-",threads),env,work.resolve("native.log"));'''
    source = replace_once(source, anchor, '''        if (stream!=null) {
            env.put("MING_STREAM_DIRECTORY",stream.toString());
            env.put("MING_STREAM_START",Integer.toString(startIndex));
        }
        Native.configure(bundle, mode, env);
        List<String> command=Arrays.asList(bundle.resolve("ming-run").toString(),bundle.resolve("models").toString(),
                work.resolve("000.i64").toString(),work.resolve("unused.wav").toString(),"-",threads);
        Path log=work.resolve("native.log");
        if (stream==null) {
            process(command,env,log);
        } else {
            try (StreamFragments publisher=new StreamFragments(stream,outputs,startIndex,request.trimSilence)) {
                process(command,env,log);
                publisher.finish();
            }
        }''')
    anchor = '        return outputs;'
    assert source.count(anchor) == 1, 'Review changed Ming fragment processing before packaging'
    source = source.replace(anchor, '        if (stream==null && request.trimSilence) for (Path pcm : outputs) SilenceTrim.trim(pcm);\n' + anchor)
    source = replace_once(source, 'concatenate(outputs,output)', 'stream==null?concatenate(outputs,output):StreamFragments.concatenate(stream,outputs.size(),output)')
    anchor = 'subList(0,1),voice,null,mode,threads,0,stream)'
    assert source.count(anchor) == 1, 'Review changed Ming first-fragment conditioning before packaging'
    return source.replace(anchor, 'subList(0,1),voice,transcript,mode,threads,0,stream)')


def replace_once(source, anchor, replacement):
    assert source.count(anchor) == 1, f'Review changed Ming source near {anchor}'
    return source.replace(anchor, replacement)


def adapt_reference_batch(source):
    source = replace_once(source, '#include "audio_job_queue.h"', '#include "audio_job_queue.h"\n#include <cstdio>\n#include <sys/stat.h>')
    anchor = '    size_t begin=0,block_begin=0;'
    source = replace_once(source, anchor, '''    const char* stream_path=std::getenv("MING_STREAM_DIRECTORY");
    const std::string stream=stream_path ? stream_path : "";
    const size_t stream_start=stream_path ? std::stoul(std::getenv("MING_STREAM_START")) : 0;
    auto check_stream=[&]() {
        struct stat info{};
        if(stat(stream.c_str(),&info)!=0 || std::ifstream(stream+"/abort"))
            throw std::runtime_error("Ming stream closed");
    };
''' + anchor)
    anchor = '        std::vector<State> states(paragraph_size);'
    source = replace_once(source, anchor, '''        if(stream_path) {
            if(paragraph_size!=1)throw std::runtime_error("Ming streaming requires one fragment per group");
            const size_t index=stream_start+begin;
            check_stream();
            // Keep at most two fragments ahead of the actual playback head.
            while(index>=2 && !std::ifstream(stream+"/played-"+std::to_string(index-2))) {
                check_stream();
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
        }
''' + anchor)
    anchor = '            for(auto item:{std::make_pair(".latent.f32",&s.latent)'
    return replace_once(source, anchor, '''            if(stream_path) {
                const std::string pcm=output+".pcm.f32";
                const std::string partial=pcm+".partial";
                write_binary(partial,s.wave);
                if(std::rename(partial.c_str(),pcm.c_str())!=0)
                    throw std::runtime_error("Cannot publish Ming fragment");
                continue;
            }
''' + anchor)


def adapt_task_request(source):
    # The official BASIC caption leaves omitted attributes null.
    allowed = '                    allowed(controls,"speed","pitch","volume");'
    assert source.count(allowed) == 1, 'Review changed Ming BASIC task before packaging'
    source = source.replace(allowed, allowed + '\n                    require(controls.length()>0,"BASIC requires task controls");')
    for field in ('speed', 'pitch', 'volume'):
        anchor = f'{field}=choice(controls,"{field}",'
        assert source.count(anchor) == 1, 'Review changed Ming BASIC attributes before packaging'
        source = source.replace(anchor, f'{field}=controls.isNull("{field}")?null:choice(controls,"{field}",')
    for anchor, replacement in (
        ('public final boolean design, zeroSpeaker, acoustic;', 'public final boolean design, zeroSpeaker, acoustic, trimSilence;'),
        ('"reference_wav","reference_text","voice_cache","seed");', '"reference_wav","reference_text","voice_cache","seed","trim_silence");\n'
         '        trimSilence=!request.has("trim_silence") || request.getBoolean("trim_silence");'),
        ('zeroSpeaker=design || task.equals("IP");',
         'boolean freeformReference=task.equals("FREEFORM") && (referenceWav!=null || voiceCache!=null);\n'
         '        zeroSpeaker=(design && !freeformReference) || task.equals("IP");'),
        ('"PARALINGUISTIC","MULTILINGUAL").contains(task);', '"PARALINGUISTIC","MULTILINGUAL").contains(task)\n'
         '                || (freeformReference && (referenceText!=null || voiceCache!=null));'),
    ):
        assert source.count(anchor) == 1, 'Review changed Ming task request before packaging'
        source = source.replace(anchor, replacement)
    return source


def stage_qnn(runtime, qnn_archive, ort_archive):
    (runtime / 'qnn').mkdir(exist_ok=True)
    (runtime / 'licenses').mkdir(exist_ok=True)
    files = ['qnn/libonnxruntime-qnn.so', 'licenses/Qualcomm-LICENSE.pdf', 'licenses/Qualcomm-NOTICE.txt']
    with zipfile.ZipFile(ort_archive) as ort:
        (runtime / files[0]).write_bytes(ort.read(JNI + 'libonnxruntime.so'))
    with zipfile.ZipFile(qnn_archive) as qnn:
        (runtime / files[1]).write_bytes(qnn.read('LICENSE.pdf'))
        (runtime / files[2]).write_bytes(qnn.read('NOTICE.txt'))
        stubs = sorted(name.removeprefix(JNI) for name in qnn.namelist()
                       if re.fullmatch(JNI + r'libQnnHtpV[0-9]+Stub\.so', name))
        if not stubs:
            raise ValueError('Qualcomm runtime has no ARM64 HTP stubs')
        for name in (*QNN_LIBRARIES, *stubs, *(stub.replace('Stub.so', 'Skel.so') for stub in stubs)):
            relative = f'qnn/{name}'
            (runtime / relative).write_bytes(qnn.read(JNI + name))
            files.append(relative)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='ming-android checkout')
    parser.add_argument('--ort-library', type=Path, required=True, help='Matching Android ONNX Runtime shared library')
    parser.add_argument('--qnn-archive', type=Path, required=True, help='Official Qualcomm qnn-runtime AAR')
    parser.add_argument('--qnn-ort-archive', type=Path, required=True, help='Official onnxruntime-android-qnn AAR')
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--ort-source', type=Path, required=True, help='ONNX Runtime checkout with headers and notices')
    parser.add_argument('--pocketfft-include', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--release-url', default=f'https://github.com/Renakoni/Voicebox/releases/download/ming-runtime-v{VERSION}')
    parser.add_argument('--catalog', type=Path, help='Also write the pinned app asset catalog here')
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    build, runtime = output / 'build' / ABI, output / 'runtime'
    build.mkdir(parents=True, exist_ok=True)
    runtime.mkdir(exist_ok=True)
    shutil.copyfile(args.ort_library, runtime / 'libonnxruntime.so')
    native = build / 'sources'
    native.mkdir(exist_ok=True)
    shutil.copyfile(source / 'native/main.cpp', native / 'main.cpp')
    batch = (source / 'native/reference_batch.h').read_text(encoding='utf-8')
    (native / 'reference_batch.h').write_text(adapt_reference_batch(batch), encoding='utf-8')
    options = (source / 'native/htp_options.h').read_text(encoding='utf-8')
    backend = '{"backend_path","libQnnHtp.so"}'
    assert options.count(backend) == 1
    (native / 'htp_options.h').write_text(options.replace(backend,
        '{"backend_path",std::getenv("MING_QNN_BACKEND") ? std::getenv("MING_QNN_BACKEND") : "libQnnHtp.so"}'),
        encoding='utf-8')
    sdk = args.sdk.resolve()
    cmake = sdk / 'cmake/3.22.1/bin/cmake.exe'
    run(cmake, '-S', ROOT, '-B', build / 'native', '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={sdk}/cmake/3.22.1/bin/ninja.exe',
        f'-DCMAKE_TOOLCHAIN_FILE={sdk}/ndk/28.2.13676358/build/cmake/android.toolchain.cmake',
        f'-DANDROID_ABI={ABI}', '-DANDROID_PLATFORM=android-30', '-DANDROID_STL=c++_static',
        f'-DMING_SOURCE={source}', f'-DMING_NATIVE={native}',
        f'-DORT_INCLUDE={args.ort_source.resolve()}/include/onnxruntime/core/session',
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
            elif name == 'TaskRequest':
                code = adapt_task_request(code)
            (java / f'{name}.java').write_text(code, encoding='utf-8')
        for helper in ('Native.java', 'SilenceTrim.java', 'StreamFragments.java'):
            shutil.copyfile(ROOT / helper, java / helper)
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
    # The package carries runtime code, a provider probe and third-party notices.
    manifest = {'contract': 1, 'id': 'ming', 'entry': 'dev.renakoni.voicebox.model.ming.MingEntry',
                'code': 'tasks.jar', 'abi': ABI, 'adapterSourceSha256': adapter_source_hash(), 'sourceCommit': subprocess.check_output(
                    ['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()}
    (runtime / 'bridge.json').write_text(json.dumps(manifest), encoding='utf-8')
    shutil.copyfile(PROBE, runtime / 'provider-probe.onnx')
    names = ['provider-probe.onnx', 'bridge.json', 'tasks.jar', 'libonnxruntime.so', 'libming-synthesize.so',
             'libming-reference.so', 'libming-ort.so', 'libming-gpu-probe.so', 'libming-mmla-neon.so',
             'libming-mmla-bf16.so', 'libming-htp.so']
    names += stage_qnn(runtime, args.qnn_archive, args.qnn_ort_archive)
    notices = {
        'ONNXRuntime-LICENSE.txt': args.ort_source / 'LICENSE',
        'ONNXRuntime-NOTICE.txt': args.ort_source / 'ThirdPartyNotices.txt',
        'Khronos-OpenCL-LICENSE.txt': source / 'native/third_party/CL/LICENSE',
        'Ming-THIRD-PARTY.txt': source / 'THIRD_PARTY.md',
        'Voicebox-LICENSE.txt': ROOT.parents[2] / 'LICENSE',
    }
    for name, path in notices.items():
        shutil.copyfile(path, runtime / 'licenses' / name)
    pocketfft = (args.pocketfft_include / 'pocketfft_hdronly.h').read_text(encoding='utf-8').split('*/', 1)[0]
    (runtime / 'licenses/pocketfft-LICENSE.txt').write_text(pocketfft.removeprefix('/*').strip() + '\n', encoding='utf-8')
    names += [f'licenses/{name}' for name in (*notices, 'pocketfft-LICENSE.txt')]
    files = [(runtime / name, name) for name in names]
    name = f'ming-backend-v{VERSION}-{ABI}.zip'
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
