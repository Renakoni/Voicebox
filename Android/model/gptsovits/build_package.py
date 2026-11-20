"""Create the built-in GPT bridge and its pinned application catalog from a variant AAR."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def archive_file(archive, name, source):
    entry = zipfile.ZipInfo(name)
    entry.compress_type = archive.compression
    with archive.open(entry, 'w') as output:
        shutil.copyfileobj(source, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('aar', 'runtime', 'zip4j', 'sdk', 'output', 'audit', 'platform'):
        parser.add_argument('--' + field, type=Path, required=True)
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--abi', required=True, choices=('arm64-v8a', 'x86_64'))
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    root = args.output
    catalog_dir, archive_dir = root / 'bridges', root / 'backend-packages'
    for folder in (catalog_dir, archive_dir):
        folder.mkdir(parents=True, exist_ok=True)
    audit = json.loads(args.audit.read_text(encoding='utf-8'))
    with tempfile.TemporaryDirectory(dir=root.parent) as tmp:
        work = Path(tmp)
        with zipfile.ZipFile(args.aar) as model, zipfile.ZipFile(args.runtime) as ort:
            jars = []
            for name, library in (('provider', model), ('ort', ort)):
                jar = work / (name + '.jar')
                jar.write_bytes(library.read('classes.jar'))
                jars.append(jar)
            code = work / 'backend.jar'
            subprocess.run([str(args.sdk / 'build-tools' / args.build_tools / ('d8.bat' if os.name == 'nt' else 'd8')), '--release', '--min-api', '30',
                            '--lib', str(args.platform),
                            '--output', str(code), *map(str, jars), str(args.zip4j)], check=True)
            adapter = hashlib.sha256()
            for path in (args.aar, args.runtime, args.zip4j, Path(__file__)):
                adapter.update(bytes.fromhex(digest(path)))
            manifest = dict(id='gptsovits', contract=1, entry='dev.renakoni.voicebox.model.gptsovits.GptEntry',
                            abi=args.abi, code='backend.jar', sourceCommit=args.commit,
                            adapterSourceSha256=adapter.hexdigest())
            package = work / ('gptsovits-' + args.abi + '.zip')
            with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(zipfile.ZipInfo('bridge.json'), json.dumps(manifest).encode())
                with code.open('rb') as source:
                    archive_file(archive, 'backend.jar', source)
                for name, expected in audit.items():
                    path = 'assets/' + name
                    data = model.read(path)
                    if Path(name).suffix in {'.json', '.txt', '.tsv'}:
                        data = data.replace(b'\r\n', b'\n')
                    if hashlib.sha256(data).hexdigest() != expected:
                        raise ValueError('Review changed bridge asset before packaging: ' + path)
                    if name.startswith('checkpoint-') and name.endswith('/manifest.json'):
                        for filename, checksum in json.loads(data)['files'].items():
                            template = name.rsplit('/', 1)[0] + '/' + filename
                            if audit.get(template) != checksum:
                                raise ValueError('Checkpoint manifest differs from packaged template: ' + template)
                    archive.writestr(zipfile.ZipInfo(path), data, compress_type=zipfile.ZIP_DEFLATED)
                for name, source in [('libc++_shared.so', model), ('libvoicebox_japanese.so', model), ('libnative_decoder_loop.so', model),
                                     ('libonnxruntime.so', ort), ('libonnxruntime4j_jni.so', ort)]:
                    with source.open('jni/' + args.abi + '/' + name) as stream:
                        archive_file(archive, name, stream)
                files = {entry.filename: entry.file_size for entry in archive.infolist()}
        sha = digest(package)
        catalog = dict(manifest, format=2, displayName='GPT-SoVITS', revision=sha, version='1', files=files, parts=[
            dict(url='asset://backend-packages/' + package.name, bytes=package.stat().st_size, sha256=sha)])
        catalog_file = work / 'gptsovits.json'
        catalog_file.write_text(json.dumps(catalog, indent=2) + '\n', encoding='utf-8')
        package.replace(archive_dir / package.name)
        catalog_file.replace(catalog_dir / catalog_file.name)
        for stale in archive_dir.glob('gptsovits-*.zip'):
            if stale.name != package.name:
                stale.unlink()


if __name__ == '__main__':
    main()
