"""Stage the Ming Android model files separately from the executable bridge."""
import argparse
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='ming-android checkout with exported models')
    parser.add_argument('--output', type=Path, required=True, help='New dense-0.5b model directory')
    parser.add_argument('--htp', action='store_true', help='Also stage the Snapdragon HTP Flow source')
    parser.add_argument('--source-model', help='Original checkpoint repository ID for these exports')
    args = parser.parse_args()

    tasks = args.source / 'artifacts/tasks-arm64-v1'
    realtime = args.source / 'artifacts/portable-arm64-guarded/models'
    files = {path.relative_to(tasks): path for folder in ('models', 'reference', 'voice', 'examples')
             for path in (tasks / folder).iterdir() if path.is_file()}
    for name in ('flow.onnx', 'aggregator.onnx'):
        files[Path('models') / name] = realtime / name
    if args.htp:
        htp = args.source / 'models/flow-batch4-attn-flat'
        for name in ('flow.onnx', 'flow.data'):
            files[Path('htp') / name] = htp / name
        for name in ('time.onnx', 'time.data'):
            files[Path('htp') / name] = realtime / name
        files[Path('models/aggregator-htp.onnx')] = args.source / 'models/aggregator-batch4/aggregator.onnx'

    missing = [str(source) for source in files.values() if not source.is_file() or not source.stat().st_size]
    if missing:
        parser.error('Missing model exports: ' + ', '.join(missing))
    args.output.mkdir(parents=True, exist_ok=False)
    for relative, source in files.items():
        target = args.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    if args.source_model == 'inclusionAI/Ming-3.0-tts-0.5B':
        shutil.copyfile(Path(__file__).with_name('official_voice_presets.json'), args.output / 'voice-presets.json')
    print(args.output)


if __name__ == '__main__':
    main()
