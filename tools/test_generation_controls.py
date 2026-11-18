"""Numerical and layout checks for add_generation_controls.py.

Usage: python tools/test_generation_controls.py --upstream ../ref/GPT-SoVITS
Requires onnxruntime, numpy and torch; no model weights needed for the unit checks.
"""
import argparse
import ast
import copy
import json
from pathlib import Path
import tempfile
import unittest
from typing import Optional

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnx import TensorProto as T, helper as h

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'Android/app/src/main/assets'
DIRECTORIES = [ASSETS / name for name in ['checkpoint-v2proplus', 'checkpoint-v2pro', 'checkpoint-v5turbo']]
UPSTREAM = ROOT.parent / 'ref/GPT-SoVITS'


def extract(model, inputs, outputs):
    """Cut a small executable graph, including lexical dependencies inside If."""
    def dependencies(node):
        names = set(node.input)
        for attr in node.attribute:
            if attr.type == onnx.AttributeProto.GRAPH:
                for child in attr.g.node:
                    names.update(dependencies(child))
        return names
    required = set(outputs)
    selected = []
    for node in reversed(model.graph.node):
        if required.intersection(node.output) and not set(node.output).issubset(inputs):
            selected.append(node)
            required.update(dependencies(node))
    graph = h.make_graph(list(reversed(selected)), 'test_controls',
        [h.make_tensor_value_info(name, dtype, shape) for name, (dtype, shape) in inputs.items()],
        [h.make_tensor_value_info(name, dtype, shape) for name, (dtype, shape) in outputs.items()],
        [t for t in model.graph.initializer if t.name in required])
    result = h.make_model(graph, opset_imports=model.opset_import, ir_version=model.ir_version)
    onnx.checker.check_model(result)
    options = ort.SessionOptions()
    options.log_severity_level = 3
    options.intra_op_num_threads = 2
    return ort.InferenceSession(result.SerializeToString(), options, providers=['CPUExecutionProvider'])


def official_sampler():
    source = UPSTREAM / 'GPT_SoVITS/AR/models/utils.py'
    definition = next(n for n in ast.parse(source.read_text(encoding='utf-8')).body
                      if isinstance(n, ast.FunctionDef) and n.name == 'logits_to_probs')
    scope = {'torch': torch, 'Optional': Optional}
    exec(compile(ast.Module(body=[definition], type_ignores=[]), str(source), 'exec'), scope)
    return scope['logits_to_probs']


def check_structure(model):
    # Treat external weights as typed inputs for structural checking. Numerical
    # checks use the real tensors; no fake/undersized tensor payloads are needed.
    check = copy.deepcopy(model)
    for tensor in list(check.graph.initializer):
        if tensor.data_location == T.EXTERNAL:
            if tensor.name not in {i.name for i in check.graph.input}:
                check.graph.input.append(h.make_tensor_value_info(tensor.name, tensor.data_type, list(tensor.dims)))
            check.graph.initializer.remove(tensor)
    onnx.checker.check_model(check)


class GenerationControlsTest(unittest.TestCase):
    def test_resplitting_keeps_speed_branch_dependencies(self):
        from split_vits_acoustic import split
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'vits_fp32.onnx'
            source.write_bytes((DIRECTORIES[0] / source.name).read_bytes())
            encoder, _ = split(source, 'split-')
            model = onnx.load(encoder, load_external_data=False)
            self.assertIn('speed', [i.name for i in model.graph.input])
            check_structure(model)

    def test_prefill_and_decoder_sampling_match_upstream(self):
        official = official_sampler()
        rng = np.random.default_rng(413)
        logits_case = rng.normal(size=1025).astype(np.float32)
        tied = logits_case.copy()
        tied[:20] = 10
        previous = np.array([0, 1, 1, 25, 700, 1024], np.int64)
        cases = [
            (logits_case, (1, 15, 1, 1.35)),  # Defaults.
            (logits_case, (0, 1, 0, 1)),      # Zero temperature and Top-P.
            (logits_case, (.5, 100, .8, 2)),  # Nucleus filtering before temperature and Top-K.
            (tied, (1, 15, 1, 1)),           # Keep ties at the Top-K threshold.
        ]
        checked = 0
        # Architectures share these semantic graphs; exercise each sampler once.
        for name in ['mobile-prefill.onnx', 'mobile-decoder-shared.onnx']:
            path = DIRECTORIES[0] / name
            model = onnx.load(path, load_external_data=False)
            scatter, = [n for n in model.graph.node if n.op_type == 'ScatterElements']
            logits, tokens = scatter.input[:2]
            output = next(n.output[0] for n in model.graph.node if n.name == '/Softmax')
            inputs = {logits: (T.FLOAT, [1025]), tokens: (T.INT64, [None])}
            session = extract(model, {**inputs, 'sampling': (T.FLOAT, [4])}, {output: (T.FLOAT, [1025])})
            for case, (temperature, k, p, penalty) in cases:
                actual = session.run(None, {logits: case, tokens: previous,
                    'sampling': np.array([temperature, k, p, penalty], np.float32)})[0]
                expected = official(torch.from_numpy(case.copy()[None]), torch.from_numpy(previous[None]),
                    temperature=temperature, top_k=k, top_p=p, repetition_penalty=penalty).numpy()[0]
                np.testing.assert_allclose(actual, expected, atol=1e-7, rtol=2e-5, equal_nan=False)
                checked += 1
        print(f'Sampling: {checked} comparisons against upstream, including defaults and Top-K ties')

    def test_speed_features_and_v5_valid_length(self):
        rng = np.random.default_rng(64)
        checked = 0
        for path in [DIRECTORIES[0] / 'split-encoder.onnx', DIRECTORIES[2] / 'v5-condition.onnx']:
            model = onnx.load(path, load_external_data=False)
            v5 = path.name == 'v5-condition.onnx'
            feature = '/enc_p/encoder2/Mul_1_output_0' if v5 else '/vq_model/enc_p/encoder2/Mul_1_output_0'
            mask = '/vq_model/enc_p/ConstantOfShape_output_0'
            inputs = {feature: (T.FLOAT, [1, 8, None]), 'speed': (T.DOUBLE, [1])}
            outputs = {'/speed/features': (T.FLOAT, [1, 8, None])}
            if not v5:
                inputs[mask] = (T.FLOAT, [1, 1, None])
                outputs['/speed/mask'] = (T.FLOAT, [1, 1, None])
            else:
                inputs['/Mul_3_output_0'] = (T.INT64, [1])
                outputs['/speed/wns1/valid_length'] = (T.INT64, [1])
            session = extract(model, inputs, outputs)
            for length, speed in [(66, .6), (2, 1), (6, 1.65)]:
                y = rng.normal(size=(1, 8, length)).astype(np.float32)
                ymask = (np.arange(length) < length - 1).astype(np.float32)[None, None]
                feed = {feature: y, 'speed': np.array([speed], np.float64)}
                feed['/Mul_3_output_0' if v5 else mask] = np.array([length * 2], np.int64) if v5 else ymask
                actual, other = session.run(None, feed)
                target = length if speed == 1 else int(length / speed) + 1
                if speed == 1:
                    np.testing.assert_array_equal(actual, y)
                else:
                    expected = torch.nn.functional.interpolate(torch.from_numpy(y), size=target, mode='linear').numpy()
                    # ORT and torch use slightly different float coordinate rounding.
                    np.testing.assert_allclose(actual, expected, atol=1e-4, rtol=3e-5)
                if v5:
                    self.assertEqual(int(other[0]), length * 2 if speed == 1 else int(length * 2 / speed) + 1)
                else:
                    expected_mask = ymask if speed == 1 else torch.nn.functional.interpolate(torch.from_numpy(ymask), size=target, mode='nearest').numpy()
                    np.testing.assert_array_equal(other, expected_mask)
                checked += 1
        print(f'Speed: {checked} feature/mask comparisons against torch interpolation and upstream lengths')

    def test_template_input_contracts(self):
        checked = set()
        for directory in DIRECTORIES:
            manifest = json.loads((directory / 'manifest.json').read_text())
            for name, sha in manifest['files'].items():
                if not name.endswith('.onnx') or sha in checked:
                    continue
                checked.add(sha)
                model = onnx.load(directory / name, load_external_data=False)
                inputs = {value.name: value.type.tensor_type for value in model.graph.input}
                if name.startswith(('mobile-', 't2s_first_stage_decoder', 't2s_stage_decoder')):
                    self.assertEqual(inputs['sampling'].elem_type, T.FLOAT)
                    self.assertEqual(inputs['sampling'].shape.dim[0].dim_value, 4)
                if name in ['vits_fp32.onnx', 'split-encoder.onnx', 'v5-condition.onnx']:
                    self.assertEqual(inputs['speed'].elem_type, T.DOUBLE)
                    self.assertEqual(inputs['speed'].shape.dim[0].dim_value, 1)
                # Check structure without materializing GBs of external weights.
                check_structure(model)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', type=Path, default=UPSTREAM)
    args, remaining = parser.parse_known_args()
    UPSTREAM = args.upstream
    unittest.main(argv=[__file__] + remaining)
