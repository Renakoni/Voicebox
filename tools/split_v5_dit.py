"""Split V5 Turbo's compact DiT without changing its external weight layout."""
import argparse
from pathlib import Path

import onnx
from onnx import helper, TensorProto


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


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    split_compact_dit(parser.parse_args().source)
