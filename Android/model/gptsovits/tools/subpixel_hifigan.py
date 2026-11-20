"""FP32 ConvTranspose1d -> low-resolution Conv + phase interleave.

Inspired by gsv.cpp's subpixel vocoder (MIT; licenses/gsv.cpp-MIT.txt), expressed as standard ONNX for
the existing V2ProPlus weights. Does not change production graph selection.
Keep weight normalization in the original graph; ORT folds the constant weight
reindexing. No checkpoint conversion, quantization or new weight file is needed.
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper


def rewrite(source: Path, output: Path, stages: set[int] | None = None, compact_phases: bool = False) -> int:
    if source.resolve() == output.resolve():
        raise ValueError("Use a separate output graph")
    model = onnx.load(source, load_external_data=False)
    initializers = {v.name: v for v in model.graph.initializer}
    result = []
    stage = 0
    changed = 0
    for node in model.graph.node:
        if node.op_type != "ConvTranspose":
            result.append(node)
            continue
        selected = stages is None or stage in stages
        stage += 1
        if not selected:
            result.append(node)
            continue
        attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
        stride, = attrs["strides"]
        kernel, = attrs["kernel_shape"]
        pad, end_pad = attrs["pads"]
        if (attrs.get("group", 1) != 1 or attrs.get("dilations", [1]) != [1]
                or attrs.get("output_padding", [0]) != [0] or "output_shape" in attrs
                or attrs.get("auto_pad", b"NOTSET") != b"NOTSET"
                or pad != end_pad or kernel - 2 * pad != stride):
            raise ValueError(f"Unsupported ConvTranspose contract: {node.name}")
        if len(node.input) != 3 or node.input[2] not in initializers:
            raise ValueError("Expected an explicit constant bias")
        channels, = initializers[node.input[2]].dims
        left = (kernel - 1 - pad) // stride
        right = (pad + stride - 1) // stride
        taps = left + right + 1
        indices = np.array([[r + pad + (left - a) * stride for a in range(taps)]
                            for r in range(stride)], dtype=np.int64)
        valid = (indices >= 0) & (indices < kernel)
        indices[~valid] = kernel  # Appended zero tap.
        prefix = node.name + "/subpixel/"

        def constant(name, value):
            full = prefix + name
            model.graph.initializer.append(numpy_helper.from_array(np.asarray(value, dtype=np.int64), full))
            return full

        def add(op, inputs, name, **attributes):
            full = prefix + name
            result.append(helper.make_node(op, inputs, [full], name=full, **attributes))
            return full

        groups = []
        for phase in range(stride):
            positions = np.flatnonzero(valid[phase])
            lo, hi = (int(positions[0]), int(positions[-1]) + 1) if compact_phases else (0, taps)
            if groups and groups[-1][2:] == [lo, hi]:
                groups[-1][1] = phase + 1
            else:
                groups.append([phase, phase + 1, lo, hi])
        padded = node.input[1] if compact_phases else add(
            "Pad", [node.input[1], constant("pads", [0, 0, 0, 0, 0, 1])], "zero_tap")
        outputs = []
        for group, (start, end, lo, hi) in enumerate(groups):
            tag = f"group{group}/"
            width, count = hi - lo, end - start
            weights = add("Gather", [padded, constant(tag + "indices", indices[start:end, lo:hi])], tag + "phase_taps", axis=2)
            weights = add("Transpose", [weights], tag + "phase_weights", perm=[1, 2, 0, 3])
            weights = add("Reshape", [weights, constant(tag + "weight_shape", [channels * count, -1, width])], tag + "weights")
            bias = add("Gather", [node.input[2], constant(tag + "bias_indices", np.repeat(np.arange(channels), count))], tag + "bias", axis=0)
            convolved = add("Conv", [node.input[0], weights, bias], tag + "conv",
                            kernel_shape=[width], strides=[1], pads=[left - lo, right - (taps - hi)])
            outputs.append(add("Reshape", [convolved, constant(tag + "phase_shape", [0, channels, count, -1])], tag + "phases"))
        phases = outputs[0] if len(outputs) == 1 else add("Concat", outputs, "phases", axis=2)
        interleaved = add("Transpose", [phases], "interleaved", perm=[0, 1, 3, 2])
        result.append(helper.make_node("Reshape", [interleaved, constant("output_shape", [0, channels, -1])],
                                       list(node.output), name=prefix + "output"))
        changed += 1
    if not changed or (stages is not None and not stages.issubset(set(range(stage)))):
        raise ValueError("No matching ConvTranspose stages")
    del model.graph.node[:]
    model.graph.node.extend(result)
    del model.graph.value_info[:]
    onnx.save(model, output)
    return changed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stages", type=int, nargs="+", help="Zero-based upsample stages; default all")
    parser.add_argument("--compact-phases", action="store_true", help="Group phases by valid taps to avoid zero-weight MACs")
    args = parser.parse_args()
    count = rewrite(args.source, args.output, set(args.stages) if args.stages else None, args.compact_phases)
    print(f"Rewrote {count} FP32 upsample stages: {args.output}")
