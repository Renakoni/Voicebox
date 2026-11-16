"""Split V5 Turbo's compact DiT without changing its external weight layout."""
import argparse
from collections import defaultdict
from pathlib import Path
import re

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto


def split_compact_dit(source: Path):
    model = onnx.load(source, load_external_data=False)
    norms = [node for node in model.graph.node if node.op_type == 'LayerNormalization']
    if len(norms) != 45:
        raise ValueError('Expected the 22-block V5 Turbo compact graph')
    aliases = {norms[0].input[0]: 'stem_hidden', norms[-1].input[0]: 'body_hidden'}
    for node in model.graph.node:
        for values in (node.input, node.output):
            for index, name in enumerate(values):
                if name in aliases:
                    values[index] = aliases[name]
    values = {value.name: value for value in model.graph.input}
    values['velocity'] = model.graph.output[0]
    for name in ('stem_hidden', 'body_hidden'):
        values[name] = helper.make_tensor_value_info(name, TensorProto.FLOAT, [1, 'frames', 1024])
    for part, boundary, output in [('stem', None, 'stem_hidden'),
                                   ('body', 'stem_hidden', 'body_hidden'),
                                   ('head', 'body_hidden', 'velocity')]:
        needed = {output}
        nodes = []
        for node in reversed(model.graph.node):
            if needed.intersection(node.output) and boundary not in node.output:
                nodes.append(node)
                needed.update(node.input)
        # A segment may still use Shape(x); retain that input so static HTP buckets
        # can fold it, without hard-coding a sequence length into the templates.
        inputs = [value for name, value in values.items() if name in needed and
                  (name in {v.name for v in model.graph.input} or name == boundary)]
        graph = helper.make_graph(list(reversed(nodes)), f'v5-dit-{part}', inputs,
                                  [values[output]], [t for t in model.graph.initializer if t.name in needed])
        result = helper.make_model(graph, opset_imports=list(model.opset_import))
        result.ir_version = model.ir_version
        onnx.save(result, source.parent / f'v5-dit-{part}.onnx')
    rewrite_htp_gelu(source.parent / 'v5-dit-body.onnx', source.parent / 'v5-dit-htp.onnx')


def rewrite_htp_gelu(source: Path, target: Path):
    """Use x*sigmoid(2*z) for .5*x*(1+tanh(z)), preserving how z is computed.

    HTP executes Sigmoid much faster than Tanh. Factoring the cubic argument
    changes FP16 rounding enough to fail waveform parity, so retain its order.
    The GPU FP32 body and external weight layout remain unchanged.
    """
    model = onnx.load(source, load_external_data=False)
    pattern = re.compile(r'^(/ff/ff/ff\.0/ff\.0\.1(?:_\d+)?)/')
    groups, consumers = defaultdict(list), defaultdict(set)
    for node in model.graph.node:
        for name in node.input:
            consumers[name].add(node.name)
        match = pattern.match(node.name)
        if match:
            groups[match[1]].append(node)
    if len(groups) != 22:
        raise ValueError('Expected 22 expanded V5 Turbo FFN activations')
    model.graph.initializer.append(numpy_helper.from_array(np.array(2., np.float32), 'gelu_two'))
    replacements, removed = {}, set()
    for prefix, nodes in groups.items():
        names = {node.name for node in nodes}
        produced = {name for node in nodes for name in node.output}
        inputs = {name for node in nodes for name in node.input} - produced
        outputs = {name for name in produced if consumers[name] - names}
        tanh = [node for node in nodes if node.op_type == 'Tanh']
        if len(inputs) != 1 or len(outputs) != 1 or len(tanh) != 1:
            raise ValueError(f'Unexpected GELU boundary: {prefix}')
        needed, before = {tanh[0].input[0]}, []
        for node in reversed(nodes):
            if needed.intersection(node.output):
                before.append(node)
                needed.update(node.input)
        argument, value = prefix + '/sigmoid_argument', prefix + '/sigmoid_value'
        replacements[nodes[0].name] = list(reversed(before)) + [
            helper.make_node('Mul', [tanh[0].input[0], 'gelu_two'], [argument], name=prefix + '/Double'),
            helper.make_node('Sigmoid', [argument], [value], name=prefix + '/Sigmoid'),
            helper.make_node('Mul', [inputs.pop(), value], [outputs.pop()], name=prefix + '/Output'),
        ]
        removed.update(names)
    rewritten = []
    for node in model.graph.node:
        if node.name in replacements:
            rewritten.extend(replacements[node.name])
        elif node.name not in removed:
            rewritten.append(node)
    del model.graph.node[:]
    model.graph.node.extend(rewritten)
    onnx.save(model, target)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    split_compact_dit(parser.parse_args().source)
