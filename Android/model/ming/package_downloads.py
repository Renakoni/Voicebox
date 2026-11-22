"""Package the three independently downloadable Ming data groups."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True, help='Prepared official dense-0.5b directory')
    parser.add_argument('--htp-source', type=Path, required=True, help='ming-android checkout with HTP exports')
    parser.add_argument('--output', type=Path, required=True, help='New release asset directory')
    parser.add_argument('--release-url', required=True, help='HTTPS base URL for the release assets')
    args = parser.parse_args()
    if not args.release_url.startswith('https://'):
        parser.error('The release URL must use HTTPS')

    clone = (
        'reference/encoder.onnx',
        'reference/encoder.data', 'reference/speaker.onnx', 'reference/campplus.onnx',
        'reference/projection.onnx', 'reference/projection.data', 'reference/mel.f32', 'reference/povey.f32',
    )
    voices = (
        'models/tokenizer.json', 'models/llm.onnx', 'models/flow.onnx', 'models/aggregator.onnx',
        'models/audio.onnx', 'models/time.onnx', 'models/window.f32',
        'models/llm.data', 'models/embedding.f32', 'models/flow.data', 'models/aggregator.data',
        'models/audio.data', 'models/time.data', 'voice/manifest.json', 'voice/speaker.f32',
        'voice/prompt.f32', 'voice/history.f32', 'examples/reference.wav', 'voice-presets.json',
    )
    htp = {
        'htp/flow.onnx': 'models/flow-batch4-attn-flat/flow.onnx',
        'htp/flow.data': 'models/flow-batch4-attn-flat/flow.data',
        'models/aggregator-htp.onnx': 'models/aggregator-batch4/aggregator.onnx',
    }
    groups = {
        'ming-clone': {name: args.model / name for name in clone},
        'ming-htp': {name: args.htp_source / source for name, source in htp.items()},
        'ming-official': {name: args.model / name for name in voices},
    }
    sources = [source for paths in groups.values() for source in paths.values()]
    invalid = [str(source) for source in sources if not source.is_file() or not 0 < source.stat().st_size < 2**31]
    if invalid:
        parser.error('Missing or oversized data files: ' + ', '.join(invalid))
    voice_catalog = json.loads((args.model / 'voice-presets.json').read_text(encoding='utf-8'))
    if voice_catalog.get('source_model') != 'inclusionAI/Ming-3.0-tts-0.5B':
        parser.error('The official weight set must include the Ming 0.5B voice catalog')

    args.output.mkdir(parents=True, exist_ok=False)
    catalog = {}
    for group, sources in groups.items():
        files = {}
        for name, source in sources.items():
            asset = name.replace('/', '-')
            target = args.output / asset
            shutil.copyfile(source, target)
            with target.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            files[name] = {'asset': asset, 'bytes': target.stat().st_size, 'sha256': digest}
        catalog[group] = files
        print(f'{group}: {len(files)} files, {sum(file["bytes"] for file in files.values()):,} bytes')
    (args.output / 'ming-models.json').write_text(
        json.dumps({'baseUrl': args.release_url.rstrip('/'), 'packs': catalog}, indent=2) + '\n',
        encoding='utf-8',
    )


if __name__ == '__main__':
    main()
