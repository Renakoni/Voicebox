"""Derive V2Pro's graphs from the corrected Plus pipeline and official tensor shapes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

import onnx
import torch

from split_vits_acoustic import split, BOUNDARY
from subpixel_hifigan import rewrite
from add_generation_controls import update as add_generation_controls


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(checkpoint, source, output):
    weights = torch.load(checkpoint, map_location="cpu", weights_only=True)["weight"]
    manifest = json.loads((source / "manifest.json").read_text())
    manifest["architecture"] = "v2Pro"
    # The Plus HTP body has different channels and upsampling kernels.
    manifest["files"] = {name: value for name, value in manifest["files"].items() if not name.startswith("v2-htp-")}
    layout = {}
    for group in manifest["groups"]:
        offset = 0
        for entry in group["tensors"]:
            if entry["checkpoint"] == "sovits":
                entry["shape"] = list(weights[entry["key"]].shape)
                entry["elements"] = weights[entry["key"]].numel()
            layout[group["file"].replace("fp16", "fp32"), entry["key"]] = (entry["shape"], offset, entry["elements"] * 4)
            offset += entry["elements"] * 4
    output.mkdir(parents=True, exist_ok=True)
    for name in manifest["files"]:
        if name.startswith("split-"):
            continue
        shutil.copyfile(source / name, output / name)
    model = onnx.load(source / "vits_fp32.onnx", load_external_data=False)
    for tensor in model.graph.initializer:
        if tensor.data_location != onnx.TensorProto.EXTERNAL:
            continue
        ext = {p.key: p.value for p in tensor.external_data}
        shape, offset, length = layout[ext["location"], tensor.name.removeprefix("vq_model.")]
        del tensor.dims[:]
        tensor.dims.extend(shape)
        for item in tensor.external_data:
            if item.key == "offset": item.value = str(offset)
            if item.key == "length": item.value = str(length)
    first_upsample = next(n for n in model.graph.node if n.name == "/vq_model/dec/ups.0/ConvTranspose")
    for attr in first_upsample.attribute:
        if attr.name == "kernel_shape": attr.ints[:] = [16]
        if attr.name == "pads": attr.ints[:] = [3, 3]
    onnx.save(model, output / "vits_fp32.onnx")
    encoder, hifigan = split(output / "vits_fp32.onnx", "split-")
    temporary = output / "split-hifigan-subpixel.onnx"
    rewrite(hifigan, temporary, compact_phases=True)
    temporary.replace(hifigan)
    (output / "split-vits.json").write_text(json.dumps({
        "schema_version": 1, "format": "genie_v2proplus_split_v1", "boundary": BOUNDARY,
        "source_sha256": sha(output / "vits_fp32.onnx"),
        "graphs": {p.name: sha(p) for p in (encoder, hifigan)},
    }, indent=2) + "\n")
    manifest["files"] = {name: sha(output / name) for name in manifest["files"]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    add_generation_controls(output)
    print(f"V2Pro: {sum(len(g['tensors']) for g in manifest['groups'])} mappings; {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--source", type=Path, default=Path("Android/model/gptsovits/src/main/assets/checkpoint-v2proplus"))
    parser.add_argument("--output", type=Path, default=Path("Android/model/gptsovits/src/main/assets/checkpoint-v2pro"))
    args = parser.parse_args()
    build(args.checkpoint, args.source, args.output)
