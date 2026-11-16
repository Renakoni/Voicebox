"""Export V5 Turbo acoustic graphs with reusable DiT conditions and external weights.

The upstream source and checkpoints are supplied locally. APK templates contain
graph structure only; converted weights are written to the requested output.
"""
import argparse
import gc
import hashlib
import json
from pathlib import Path
import shutil
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper
import torch
from torch import nn
from torch.nn import functional as F

from subpixel_hifigan import rewrite


class Reference(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.ref_enc = model.ref_enc

    def forward(self, spec):
        mask = torch.ones_like(spec[:, :1])
        return self.ref_enc(spec[:, :704], mask)


class Condition(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.quantizer, self.enc_p = model.quantizer, model.enc_p
        self.bridge, self.wns1 = model.bridge, model.wns1

    def forward(self, codes, phones, ge):
        quantized = F.interpolate(self.quantizer.decode(codes), scale_factor=2, mode='nearest')
        lengths = torch.ones(codes.shape[0], dtype=torch.long, device=codes.device) * codes.shape[-1] * 2
        text_lengths = torch.ones(phones.shape[0], dtype=torch.long, device=phones.device) * phones.shape[-1]
        x = self.enc_p(quantized, lengths, phones, text_lengths, ge)[0]
        features = F.interpolate(self.bridge(x), scale_factor=2, mode='nearest')
        return self.wns1(features, lengths * 2, ge)[0]


class StaticCondition(nn.Module):
    def __init__(self, dit):
        super().__init__()
        self.cfm = nn.Module()
        self.cfm.estimator = dit

    def forward(self, prompt, features):
        dit = self.cfm.estimator
        length = prompt.shape[-1]
        text = dit.text_embed(features.transpose(1, 2), length)
        projection = dit.input_embed.proj
        condition = F.linear(torch.cat((prompt.transpose(1, 2), text), dim=-1),
                             projection.weight[:, 100:], projection.bias)
        return condition, dit.rotary_embed.forward_from_seq_len(length)[0]


class DiTStep(nn.Module):
    def __init__(self, dit):
        super().__init__()
        self.cfm = nn.Module()
        self.cfm.estimator = dit

    def forward(self, x, condition, rope, time):
        dit = self.cfm.estimator
        t = dit.time_embed(time)
        x = F.linear(x.transpose(1, 2), dit.input_embed.proj.weight[:, :100]) + condition
        x = dit.input_embed.conv_pos_embed(x) + x
        # A single unpadded sequence has an all-true mask; no quadratic mask is needed.
        x = dit.run_blocks(x, t, None, (rope, 1.0))
        return dit.head(x, t).transpose(1, 2)


class TimeCondition(nn.Module):
    """These projections depend only on four fixed timesteps, not on the speech."""
    def __init__(self, dit):
        super().__init__()
        self.cfm = nn.Module()
        self.cfm.estimator = dit

    def forward(self, time):
        dit = self.cfm.estimator
        t = F.silu(dit.time_embed(time))
        return (torch.stack([block.attn_norm.linear(t).reshape(6, 1024) for block in dit.transformer_blocks]),
                dit.norm_out.linear(t).reshape(2, 1024))


class CompactDiTStep(nn.Module):
    """Standard attention for GPU/HTP, with exact unpadded convolution boundaries."""
    def __init__(self, dit):
        super().__init__()
        self.cfm = nn.Module()
        self.cfm.estimator = dit

    @staticmethod
    def mish(x):
        # QNN does not support Softplus; this stable form avoids exp overflow.
        return x * torch.tanh(torch.log1p(torch.exp(-torch.abs(x))) + torch.clamp(x, min=0))

    def forward(self, x, condition, cos, sin, modulation, final, mask):
        dit = self.cfm.estimator
        length = x.shape[-1]
        x = (F.linear(x.transpose(1, 2), dit.input_embed.proj.weight[:, :100]) + condition) * mask
        conv = dit.input_embed.conv_pos_embed.conv1d
        h = self.mish(conv[0](x.transpose(1, 2)).transpose(1, 2)) * mask
        h = self.mish(conv[2](h.transpose(1, 2)).transpose(1, 2))
        x = (x + h) * mask
        key_mask = (1 - mask.transpose(1, 2).unsqueeze(1)) * -10000.

        def rotary(t):
            # Upstream rotates the first 64 channels BEFORE splitting into heads.
            first = t[:, :, :64].reshape(1, length, 32, 2)
            rotated = torch.stack((-first[:, :, :, 1], first[:, :, :, 0]), dim=-1).reshape(1, length, 64)
            return torch.cat((t[:, :, :64] * cos + rotated * sin, t[:, :, 64:]), dim=-1)

        for i, block in enumerate(dit.transformer_blocks):
            shift, scale, gate, ff_shift, ff_scale, ff_gate = modulation[i].unbind(0)
            h = F.layer_norm(x, (1024,), eps=1e-6) * (1 + scale) + shift
            attn = block.attn
            q = rotary(attn.to_q(h)).reshape(1, length, 16, 64).transpose(1, 2)
            k = rotary(attn.to_k(h)).reshape(1, length, 16, 64).permute(0, 2, 3, 1)
            v = attn.to_v(h).reshape(1, length, 16, 64).transpose(1, 2)
            weights = torch.softmax((q * (64 ** -.25)) @ (k * (64 ** -.25)) + key_mask, dim=-1)
            h = (weights @ v).transpose(1, 2).reshape(1, length, 1024)
            x = x + gate * attn.to_out[0](h)
            h = F.layer_norm(x, (1024,), eps=1e-6) * (1 + ff_scale) + ff_shift
            x = (x + ff_gate * block.ff(h)) * mask
        x = F.layer_norm(x, (1024,), eps=1e-6) * (1 + final[0]) + final[1]
        return (dit.proj_out(x) * mask).transpose(1, 2)


class ReferenceMel(nn.Module):
    def __init__(self):
        super().__init__()
        from librosa.filters import mel
        self.register_buffer('window', torch.hann_window(1280))
        self.register_buffer('mel_basis', torch.from_numpy(mel(sr=32000, n_fft=1280, n_mels=100, fmin=0, fmax=None)))

    def forward(self, audio):
        padded = F.pad(audio.unsqueeze(1), (480, 480), mode='reflect').squeeze(1)
        spectrum = torch.stft(padded, 1280, 320, 1280, self.window, center=False, return_complex=False)
        magnitude = torch.sqrt(spectrum.square().sum(-1) + 1e-8)
        mel = torch.log(torch.clamp(torch.matmul(self.mel_basis, magnitude), min=1e-5))
        return (mel + 12) / 7 - 1


class Vocoder(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.generator = model

    def forward(self, mel):
        return self.generator((mel + 1) * 7 - 12).flatten()


class ReferenceAudio(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.ref_enc = model.ref_enc
        self.mel = ReferenceMel()
        self.register_buffer('window', torch.hann_window(2048))

    def forward(self, audio):
        padded = F.pad(audio.unsqueeze(1), (704, 704), mode='reflect').squeeze(1)
        spectrum = torch.stft(padded, 2048, 640, 2048, self.window, center=False, return_complex=False)
        spec = torch.sqrt(spectrum.square().sum(-1) + 1e-8)
        return self.ref_enc(spec[:, :704], torch.ones_like(spec[:, :1])), self.mel(audio)


def fuse_attention(path):
    """Replace V5's unmasked SDPA subgraphs with ORT's fused CPU attention."""
    model = onnx.load(path, load_external_data=False)
    by_name = {node.name: node for node in model.graph.node}
    for index in range(22):
        prefix = f'/transformer_blocks.{index}/attn/'
        node = by_name[prefix + 'Reshape_7']
        node.CopyFrom(helper.make_node('MultiHeadAttention',
            [by_name[prefix + name].input[0] for name in ('Reshape_4', 'Reshape_5', 'Reshape_6')],
            list(node.output), name=prefix + 'FusedAttention', domain='com.microsoft', num_heads=16, scale=0.125))
    needed = {value.name for value in model.graph.output}
    nodes = []
    for node in reversed(model.graph.node):
        if needed.intersection(node.output):
            nodes.append(node)
            needed.update(node.input)
    del model.graph.node[:]
    model.graph.node.extend(reversed(nodes))
    model.opset_import.append(helper.make_opsetid('com.microsoft', 1))
    onnx.save(model, path)


class Exporter:
    def __init__(self, output, checkpoint_weights):
        self.output = output
        self.weights = checkpoint_weights
        self.layout = {}
        self.groups = {}
        output.mkdir(parents=True, exist_ok=True)

    def export(self, module, name, args, inputs, outputs, dynamic, checkpoint='sovits', prefix=''):
        module.eval()
        target = self.output / (name + '.onnx')
        with torch.inference_mode():
            torch.onnx.export(module, args, target, input_names=inputs, output_names=outputs,
                              dynamic_axes=dynamic, opset_version=17, export_params=False,
                              do_constant_folding=False)
        graph = onnx.load(target)
        buffers = dict(module.named_buffers())
        for value in list(graph.graph.input):
            if value.name in inputs:
                continue
            key = value.name.removeprefix(prefix) if prefix else value.name
            if key in self.weights.get(checkpoint, {}):
                tensor = self.weights[checkpoint][key]
                group_name = 'v5_' + checkpoint + ('_fp32.bin' if checkpoint == 'vocoder' else '_fp16.bin')
                entries = self.groups.setdefault(group_name, [])
                if (checkpoint, key) not in self.layout:
                    offset = sum(e['elements'] for e in entries) * 4
                    entry = {'checkpoint': checkpoint, 'key': key, 'shape': list(tensor.shape), 'elements': tensor.numel()}
                    entries.append(entry)
                    self.layout[checkpoint, key] = offset
                initializer = onnx.TensorProto(name=value.name, data_type=onnx.TensorProto.FLOAT, dims=list(tensor.shape))
                initializer.data_location = onnx.TensorProto.EXTERNAL
                for k, v in {'location': group_name.replace('fp16', 'fp32'),
                             'offset': self.layout[checkpoint, key], 'length': tensor.numel() * 4}.items():
                    item = initializer.external_data.add(); item.key, item.value = k, str(v)
            elif value.name in buffers:
                initializer = numpy_helper.from_array(buffers[value.name].detach().cpu().numpy(), value.name)
            else:
                raise ValueError(f'Unmapped initializer {name}: {value.name}')
            graph.graph.initializer.append(initializer)
            graph.graph.input.remove(value)
        onnx.save(graph, target)
        print(name, len(graph.graph.node), 'nodes', flush=True)
        return target

    def write_weights(self):
        for name, entries in self.groups.items():
            with (self.output / name).open('wb') as stored:
                for entry in entries:
                    tensor = self.weights[entry['checkpoint']][entry['key']]
                    tensor = tensor.half() if 'fp16' in name else tensor.float()
                    stored.write(tensor.numpy().tobytes())
            if 'fp16' in name:
                with (self.output / name).open('rb') as source, (self.output / name.replace('fp16', 'fp32')).open('wb') as full:
                    while chunk := source.read(2 * 1024 * 1024):
                        full.write(np.frombuffer(chunk, dtype='<f2').astype('<f4').tobytes())
        manifest = {'schema_version': 1, 'architecture': 'v5turbo', 'sample_rate': 48000,
                    'cfm_steps': 4, 'noise_temperature': .875,
                    'groups': [{'file': name, 'precision': 'fp16' if 'fp16' in name else 'fp32', 'tensors': entries} for name, entries in self.groups.items()],
                    'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.output.glob('*.onnx')}}
        (self.output / 'acoustic-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--vocoder', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gpt', type=Path, help='Also assemble the existing optimized semantic decoder and import manifest')
    args = parser.parse_args()
    source = args.source.resolve()
    sys.path[:0] = [str(source/'GPT_SoVITS'), str(source)]
    from module.models import SynthesizerTrnV3, Generator
    torch.set_num_threads(4)
    checkpoint = torch.load(args.checkpoint, map_location='cpu', weights_only=True, mmap=True)
    vocoder_weights = torch.load(args.vocoder, map_location='cpu', weights_only=True, mmap=True)
    exporter = Exporter(args.output, {'sovits': checkpoint['weight'], 'vocoder': vocoder_weights})
    model = SynthesizerTrnV3(1025, 32, version='v5turbo', **checkpoint['config']['model']).eval()
    model.load_state_dict(checkpoint['weight'], strict=True, assign=True)
    model.float()
    # Official V5 chunks contain at most 1000 frames, including the reference.
    model.cfm.estimator.text_embed.freqs_cis = model.cfm.estimator.text_embed.freqs_cis[:1000]
    model.cfm.estimator.text_embed.precompute_max_pos = 1000
    codes, phones = torch.zeros(1,1,8,dtype=torch.long), torch.ones(1,7,dtype=torch.long)
    ge = torch.zeros(1,512,1)
    exporter.export(Reference(model), 'v5-reference', (torch.ones(1,1025,32),), ['spec'], ['ge'], {'spec':{2:'frames'}})
    exporter.export(Condition(model), 'v5-condition', (codes,phones,ge), ['codes','phones','ge'], ['features'],
                    {'codes':{2:'tokens'},'phones':{1:'phones'},'features':{2:'frames'}})
    exporter.export(ReferenceMel(), 'v5-mel', (torch.zeros(1,6400),), ['audio'], ['mel'],
                    {'audio':{1:'samples'},'mel':{2:'frames'}})
    exporter.export(ReferenceAudio(model), 'v5-reference-audio', (torch.zeros(1,6400),), ['audio'], ['ge','mel'],
                    {'audio':{1:'samples'},'mel':{2:'frames'}})
    exporter.export(StaticCondition(model.cfm.estimator), 'v5-static', (torch.zeros(1,100,32),torch.zeros(1,512,32)),
                    ['prompt','features'], ['condition','rope'],
                    {'prompt':{2:'frames'},'features':{2:'frames'},'condition':{1:'frames'},'rope':{1:'frames'}})
    exporter.export(DiTStep(model.cfm.estimator), 'v5-dit',
                    (torch.zeros(1,100,32),torch.zeros(1,32,1024),torch.zeros(1,32,64),torch.zeros(1)),
                    ['x','condition','rope','time'], ['velocity'],
                    {'x':{2:'frames'},'condition':{1:'frames'},'rope':{1:'frames'},'velocity':{2:'frames'}})
    fuse_attention(args.output/'v5-dit.onnx')
    exporter.export(TimeCondition(model.cfm.estimator), 'v5-time', (torch.zeros(1),),
                    ['time'], ['modulation', 'final'], {})
    exporter.export(CompactDiTStep(model.cfm.estimator), 'v5-dit-compact',
                    (torch.zeros(1,100,32), torch.zeros(1,32,1024), torch.ones(1,32,64), torch.zeros(1,32,64),
                     torch.zeros(22,6,1024), torch.zeros(2,1024), torch.ones(1,32,1)),
                    ['x','condition','cos','sin','modulation','final','mask'], ['velocity'],
                    {'x':{2:'frames'}, 'condition':{1:'frames'}, 'cos':{1:'frames'}, 'sin':{1:'frames'},
                     'mask':{1:'frames'}, 'velocity':{2:'frames'}})
    from split_v5_dit import split_compact_dit
    split_compact_dit(args.output/'v5-dit-compact.onnx')
    del model
    gc.collect()
    vocoder = Generator(100,'1',[3,7,11],[[1,3,5]]*3,[10,6,2,2,2],512,[20,12,4,4,4],gin_channels=0,is_bias=True)
    vocoder.remove_weight_norm()
    vocoder.load_state_dict(vocoder_weights, strict=True)
    original = exporter.export(Vocoder(vocoder), 'v5-vocoder', (torch.zeros(1,100,8),), ['mel'], ['audio'],
                               {'mel':{2:'frames'},'audio':{0:'samples'}}, checkpoint='vocoder', prefix='generator.')
    rewrite(original, args.output/'v5-vocoder-subpixel.onnx', compact_phases=True)
    exporter.write_weights()
    if args.gpt:
        assemble_import(args.output, args.gpt, checkpoint['weight'])


def assemble_import(output, gpt_path, sovits):
    base = Path(__file__).resolve().parents[1]/'Android/app/src/main/assets/checkpoint-v2proplus'
    manifest = json.loads((base/'manifest.json').read_text())
    acoustic = json.loads((output/'acoustic-manifest.json').read_text())
    manifest['architecture'] = 'v5turbo'
    manifest['groups'] = [g for g in manifest['groups'] if g['file'].startswith('t2s_')]
    gpt = torch.load(gpt_path, map_location='cpu', weights_only=True, mmap=True)['weight']
    for group in manifest['groups']:
        with (output/group['file']).open('wb') as stream:
            for entry in group['tensors']:
                tensor = (gpt if entry['checkpoint']=='gpt' else sovits)[entry['key']]
                assert list(tensor.shape)==entry['shape']
                stream.write((tensor.half() if group['precision']=='fp16' else tensor.float()).numpy().tobytes())
    manifest['groups'] += [g for g in acoustic['groups'] if g['tensors'][0]['checkpoint']=='sovits']
    manifest['files'] = {name:sha for name,sha in manifest['files'].items() if name.startswith(('t2s_','mobile-'))}
    for name in manifest['files']:
        shutil.copyfile(base/name, output/name)
    for name in ('v5-reference-audio.onnx','v5-condition.onnx','v5-static.onnx','v5-dit.onnx',
                 'v5-time.onnx','v5-dit-compact.onnx','v5-dit-stem.onnx','v5-dit-body.onnx','v5-dit-head.onnx'):
        manifest['files'][name]=acoustic['files'][name]
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    runtime=output.parent/'RuntimeAssets/v5-vocoder'
    runtime.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(output/'v5-vocoder-subpixel.onnx',runtime/'v5-vocoder-subpixel.onnx')
    shutil.copyfile(output/'v5_vocoder_fp32.bin',runtime/'v5_vocoder_fp32.bin')


if __name__ == '__main__':
    main()
