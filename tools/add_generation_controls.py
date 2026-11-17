"""Add per-request sampling/speed inputs to the shipped templates, without weights.

Run after exporting/splitting templates. Idempotent; manifests are updated last.
Requires numpy and onnx. Graphs retain their external tensor offsets and lengths.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto as T, helper as h, numpy_helper as nh


class Nodes:
    def __init__(self, prefix):
        self.prefix, self.nodes = prefix, []

    def op(self, kind, inputs, name, outputs=None, **attrs):
        names = outputs or [self.prefix + name]
        self.nodes.append(h.make_node(kind, inputs, names, name=self.prefix + name, **attrs))
        return names[0]

    def const(self, name, value, dtype):
        return self.op('Constant', [], name, value=nh.from_array(np.asarray(value, dtype=dtype)))


def sampling(model):
    graph = model.graph
    if any(i.name == 'sampling' for i in graph.input):
        return
    by_output = {o: n for n in graph.node for o in n.output}
    scatter, = [n for n in graph.node if n.op_type == 'ScatterElements']
    where = by_output[scatter.input[2]]
    mul, div = (by_output[where.input[i]] for i in (1, 2))
    assert mul.op_type == 'Mul' and div.op_type == 'Div'
    temperature, = [n for n in graph.node if n.op_type == 'Div' and n.input[0] == scatter.output[0]]
    topk, = [n for n in graph.node if n.op_type == 'TopK']
    replaced = [mul.input[1], div.input[1], temperature.input[1], topk.input[1]]
    graph.input.append(h.make_tensor_value_info('sampling', T.FLOAT, [4]))
    b = Nodes('/controls/')
    controls = [b.op('Gather', ['sampling', b.const('index_' + str(i), i, np.int64)], name, axis=0)
                for i, name in enumerate(['temperature', 'top_k', 'top_p', 'penalty'])]
    temp = b.op('Max', [controls[0], b.const('min_temperature', 1e-5, np.float32)], 'safe_temperature')
    k = b.op('Cast', [controls[1]], 'integer_k', to=T.INT64)
    k = b.op('Unsqueeze', [k, b.const('axis', [0], np.int64)], 'k')
    mul.input[1] = div.input[1] = controls[3]
    topk.input[1] = k
    temperature.input[1] = temp

    # Upstream filters cumulative probability BEFORE temperature and Top-K,
    # removes the crossing item, and retains only the first item unconditionally.
    p = Nodes('/controls/nucleus/')
    logits = scatter.output[0]
    shape = p.op('Shape', [logits], 'shape')
    sorted_values, indices = '/controls/nucleus/sorted', '/controls/nucleus/indices'
    p.op('TopK', [logits, shape], 'sort', [sorted_values, indices], axis=-1, largest=1, sorted=1)
    probs = p.op('Softmax', [sorted_values], 'probs', axis=-1)
    zero = p.const('zero', 0, np.int64)
    cumulative = p.op('CumSum', [probs, zero], 'cumulative')
    removed = p.op('Greater', [cumulative, controls[2]], 'over_p')
    size = p.op('Size', [logits], 'size')
    positions = p.op('Range', [zero, size, p.const('one', 1, np.int64)], 'positions')
    removed = p.op('And', [removed, p.op('Greater', [positions, zero], 'after_first')], 'remove')
    filtered = p.op('Where', [removed, p.const('minus_inf', -np.inf, np.float32), sorted_values], 'filtered')
    filtered = p.op('ScatterElements', [logits, indices, filtered], 'unsorted', axis=0)
    then = h.make_graph(p.nodes, 'nucleus', [], [h.make_tensor_value_info(filtered, T.FLOAT, [None])])
    identity = h.make_node('Identity', [logits], ['/controls/unfiltered'])
    otherwise = h.make_graph([identity], 'no_nucleus', [], [h.make_tensor_value_info('/controls/unfiltered', T.FLOAT, [None])])
    condition = b.op('Less', [controls[2], b.const('one', 1, np.float32)], 'filter_top_p')
    branch = h.make_node('If', [condition], ['/controls/logits'], name='/controls/top_p_branch', then_branch=then, else_branch=otherwise)
    temperature.input[0] = branch.output[0]
    nodes = list(graph.node)
    nodes.insert(nodes.index(temperature), branch)
    del graph.node[:]
    graph.node.extend(b.nodes + nodes)
    # Remove only constant/reshape chains made unused by these replacements.
    while replaced:
        value = replaced.pop()
        producer = next((n for n in graph.node if value in n.output), None)
        if producer is None or producer.op_type not in ('Constant', 'Reshape'):
            continue
        if any(value in n.input for n in graph.node) or any(o.name == value for o in graph.output):
            continue
        replaced.extend(producer.input)
        graph.node.remove(producer)


def scaled_length(b, original):
    floating = b.op('Cast', [original], 'float_length', to=T.DOUBLE)
    divided = b.op('Div', [floating, 'speed'], 'scaled_length')
    integer = b.op('Cast', [divided], 'truncated_length', to=T.INT64)
    return b.op('Add', [integer, b.const('one_frame', [1], np.int64)], 'length')


def resized_features(feature, mask=None):
    b = Nodes('/speed/resize/')
    shape = b.op('Shape', [feature], 'shape')
    last = b.const('time_axis', [2], np.int64)
    original = b.op('Gather', [shape, last], 'original_length', axis=0)
    length = scaled_length(b, original)
    prefix = b.op('Gather', [shape, b.const('prefix_axes', [0, 1], np.int64)], 'prefix', axis=0)
    sizes = b.op('Concat', [prefix, length], 'sizes', axis=0)
    y = b.op('Resize', [feature, '', '', sizes], 'features', mode='linear', coordinate_transformation_mode='half_pixel')
    outputs = [h.make_tensor_value_info(y, T.FLOAT, [None, None, None])]
    if mask:
        mask_shape = b.op('Shape', [mask], 'mask_shape')
        mask_prefix = b.op('Gather', [mask_shape, '/speed/resize/prefix_axes'], 'mask_prefix', axis=0)
        mask_sizes = b.op('Concat', [mask_prefix, length], 'mask_sizes', axis=0)
        resized = b.op('Resize', [mask, '', '', mask_sizes], 'mask', mode='nearest',
                       coordinate_transformation_mode='asymmetric', nearest_mode='floor')
        outputs.append(h.make_tensor_value_info(resized, T.FLOAT, [None, None, None]))
    return h.make_graph(b.nodes, 'resize_features', [], outputs)


def speed(model, v5=False):
    graph = model.graph
    if any(i.name == 'speed' for i in graph.input):
        return
    graph.input.append(h.make_tensor_value_info('speed', T.DOUBLE, [1]))
    feature = '/enc_p/encoder2/Mul_1_output_0' if v5 else '/vq_model/enc_p/encoder2/Mul_1_output_0'
    mask = None if v5 else '/vq_model/enc_p/ConstantOfShape_output_0'
    nodes = list(graph.node)
    index = next(i for i, n in enumerate(nodes) if feature in n.output) + 1
    b = Nodes('/speed/')
    one = b.const('one', [1], np.float64)
    equal = b.op('Equal', ['speed', one], 'is_default_vector')
    equal = b.op('Squeeze', [equal], 'is_default')
    inputs = [feature] + ([mask] if mask else [])
    unchanged = h.make_graph([h.make_node('Identity', [x], [f'/speed/default_{i}']) for i, x in enumerate(inputs)],
                             'original_features', [], [h.make_tensor_value_info(f'/speed/default_{i}', T.FLOAT, [None, None, None]) for i in range(len(inputs))])
    outputs = ['/speed/features'] + (['/speed/mask'] if mask else [])
    b.op('If', [equal], 'features_branch', outputs, then_branch=unchanged, else_branch=resized_features(feature, mask))
    for n in nodes[index:]:
        for i, value in enumerate(n.input):
            if value in inputs:
                n.input[i] = outputs[inputs.index(value)]
    nodes[index:index] = b.nodes
    if v5:
        # Physical length is 2*(floor(2N/speed)+1); valid wns1 length is floor(4N/speed)+1.
        length_node = next(n for n in nodes if n.name == '/Mul_3')
        original = length_node.output[0]
        pos = nodes.index(length_node) + 1
        calc = Nodes('/speed/wns1/')
        length = scaled_length(calc, original)
        valid = calc.op('Where', [equal, original, length], 'valid_length')
        for n in nodes[pos:]:
            for i, value in enumerate(n.input):
                if value == original:
                    n.input[i] = valid
        nodes[pos:pos] = calc.nodes
    del graph.node[:]
    graph.node.extend(nodes)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def update(directory):
    manifest_path = directory / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    for name in manifest['files']:
        transform = sampling if name in ['t2s_first_stage_decoder_fp32.onnx', 't2s_stage_decoder_fp32.onnx',
            'mobile-prefill.onnx', 'mobile-decoder.onnx', 'mobile-decoder-shared.onnx'] else (
            speed if name in ['vits_fp32.onnx', 'split-encoder.onnx', 'v5-condition.onnx'] else None)
        if transform:
            path = directory / name
            model = onnx.load(path, load_external_data=False)
            before = [(t.name, list(t.dims), t.data_type, str(t.external_data)) for t in model.graph.initializer]
            if transform == speed:
                speed(model, v5=name == 'v5-condition.onnx')
            else:
                sampling(model)
            assert before == [(t.name, list(t.dims), t.data_type, str(t.external_data)) for t in model.graph.initializer]
            # Checker resolves external files; this pass intentionally works without weights.
            path.write_bytes(model.SerializeToString())
    mobile = directory / 'mobile-decoder.json'
    info = json.loads(mobile.read_text())
    for key in ['sources', 'graphs']:
        info[key] = {name: sha(directory / name) for name in info[key]}
    info['shared_cache']['sha256'] = sha(directory / 'mobile-decoder-shared.onnx')
    mobile.write_text(json.dumps(info, indent=2) + '\n')
    split = directory / 'split-vits.json'
    if split.exists():
        info = json.loads(split.read_text())
        info['source_sha256'] = sha(directory / 'vits_fp32.onnx')
        info['graphs'] = {name: sha(directory / name) for name in info['graphs']}
        split.write_text(json.dumps(info, indent=2) + '\n')
    manifest['files'] = {name: sha(directory / name) for name in manifest['files']}
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Updated {directory}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='*', type=Path)
    args = parser.parse_args()
    for directory in args.directories or [Path(__file__).resolve().parents[1] / 'Android/app/src/main/assets' / name
            for name in ['checkpoint-v2proplus', 'checkpoint-v2pro', 'checkpoint-v5turbo']]:
        update(directory)
