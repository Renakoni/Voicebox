"""Extract the fixed 0.6B architecture, tokenizer and mixed-Q4 layout for the mobile importer."""
import json
import struct


def integer(stream, size):
    data = stream.read(size)
    if len(data) != size:
        raise EOFError("Incomplete GGUF header")
    return int.from_bytes(data, "little")


def string(stream):
    return stream.read(integer(stream, 8)).decode("utf-8")


def skip_values(stream, kind, count):
    sizes = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
    if kind == 8:
        for _ in range(count):
            stream.seek(integer(stream, 8), 1)
    elif kind == 9:
        for _ in range(count):
            skip_values(stream, integer(stream, 4), integer(stream, 8))
    else:
        stream.seek(sizes[kind] * count, 1)


def checkpoint_assets(origin, quantized):
    with (origin / "model.safetensors").open("rb") as source:
        header = json.loads(source.read(integer(source, 8)))
    header.pop("__metadata__", None)
    config = json.loads((origin / "config.json").read_bytes())
    def select(obj, keys):
        return {key: obj[key] for key in keys.split()}

    top = select(config, "model_type tokenizer_type tts_model_size im_start_token_id im_end_token_id "
                         "tts_bos_token_id tts_eos_token_id tts_pad_token_id")
    common = ("model_type hidden_size intermediate_size num_hidden_layers num_attention_heads num_key_value_heads "
              "head_dim vocab_size max_position_embeddings rope_theta rms_norm_eps num_code_groups hidden_act")
    talker = config["talker_config"]
    top["talker_config"] = select(talker, common + " text_hidden_size text_vocab_size rope_scaling position_id_per_seconds")
    top["talker_config"].update({key: value for key, value in talker.items() if key.startswith("codec_")})
    top["talker_config"]["code_predictor_config"] = select(talker["code_predictor_config"], common)
    with quantized.open("rb") as model:
        if model.read(8) != b"GGUF\x03\x00\x00\x00":
            raise ValueError("Expected a GGUF v3 template")
        tensors, fields = integer(model, 8), integer(model, 8)
        if tensors != len(header):
            raise ValueError("Official checkpoint and Q4 tensor inventories differ")
        metadata = bytearray()
        kept = 0
        for _ in range(fields):
            start = model.tell()
            key = string(model)
            skip_values(model, integer(model, 4), 1)
            if key not in {"general.name", "qwen3-tts.model_type"}:
                end = model.tell()
                model.seek(start)
                metadata.extend(model.read(end - start))
                kept += 1
        layout = {}
        # Pinned convert_talker_base emits tensors in sorted safetensors key order.
        # The quantizer changes types and offsets while preserving that inventory.
        for key in sorted(header):
            name = string(model)
            dims = [integer(model, 8) for _ in range(integer(model, 4))]
            kind, _offset = struct.unpack("<IQ", model.read(12))
            shape = header[key]["shape"]
            if (list(reversed(shape)) != dims + [1] * (len(shape) - len(dims))
                    or kind not in (0, 1, 12, 14)
                    or key.startswith("speaker_encoder.") != name.startswith("spk_enc.")):
                raise ValueError(f"Unexpected Q4 layout: {key} / {name}")
            layout[key] = dict(name=name, shape=shape, dims=dims, type=kind)
    catalog = dict(config=top, speakerEncoder=config["speaker_encoder_config"], fields=kept, tensors=layout)
    return {"assets/checkpoint-metadata.bin": bytes(metadata),
            "assets/checkpoint-layout.json": json.dumps(catalog, separators=(",", ":")).encode()}
