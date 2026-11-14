"""Prepare offline Japanese/English frontends. Run with the parity Python environment."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import pickle
import shutil
import tarfile
import urllib.request

import numpy as np
import onnx
from onnx import helper as h, numpy_helper as nh, TensorProto as T

ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT.parent / "assets/downloads"
OUT = ROOT.parent / "assets/language-frontends"
REVISION = "52b17272e0b7032415e85ad37b551db2386b1810"


def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".part")
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(path)
    return path


class DataOnlyPickle(pickle.Unpickler):
    def find_class(self, module, name):
        raise ValueError(f"Unexpected pickle global {module}.{name}")


def manifest(folder, source):
    files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(folder.iterdir()) if p.is_file() and p.name != "manifest.json"}
    (folder / "manifest.json").write_text(json.dumps(dict(source=source, files=files), indent=2) + "\n")


def english_model(weights, prefix, output):
    # NumPy upstream GRU uses reset/update/new; ONNX uses update/reset/new.
    def reorder(a):
        r, z, n = np.split(a, 3, axis=0)
        return np.concatenate([z, r, n], axis=0)
    hidden = weights[prefix + "_w_hh"].shape[1]
    init = {"embedding": weights[prefix + "_emb"],
            "W": reorder(weights[prefix + "_w_ih"])[None],
            "R": reorder(weights[prefix + "_w_hh"])[None],
            "B": np.concatenate([reorder(weights[prefix + "_b_ih"]),
                                 reorder(weights[prefix + "_b_hh"])])[None],
            "axes": np.array([1], np.int64)}
    nodes = [h.make_node("Gather", ["embedding", "tokens"], ["embedded"]),
             h.make_node("Unsqueeze", ["embedded", "axes"], ["sequence"])]
    inputs = [h.make_tensor_value_info("tokens", T.INT64, ["length"])]
    gru_inputs = ["sequence", "W", "R", "B"]
    if prefix == "dec":
        inputs += [h.make_tensor_value_info("hidden", T.FLOAT, [1, 1, hidden])]
        gru_inputs += ["", "hidden"]
    nodes += [h.make_node("GRU", gru_inputs, ["Y", "state"], hidden_size=hidden, linear_before_reset=1)]
    outputs = [h.make_tensor_value_info("state", T.FLOAT, [1, 1, hidden])]
    if prefix == "dec":
        init.update(fc=weights["fc_w"].T, bias=weights["fc_b"])
        nodes += [h.make_node("MatMul", ["state", "fc"], ["linear"]),
                  h.make_node("Add", ["linear", "bias"], ["logits"]),
                  h.make_node("ArgMax", ["logits"], ["prediction"], axis=2, keepdims=0)]
        outputs += [h.make_tensor_value_info("prediction", T.INT64, [1, 1])]
    model = h.make_model(h.make_graph(nodes, "Genie English G2P " + prefix, inputs, outputs,
                                    [nh.from_array(v, k) for k, v in init.items()]),
                         opset_imports=[h.make_opsetid("", 17)], ir_version=8)
    onnx.checker.check_model(model)
    onnx.save(model, output)


def main():
    jp = DOWNLOADS / "open-jtalk"
    archive = download("https://github.com/r9y9/open_jtalk/releases/download/v1.11.1/open_jtalk_dic_utf_8-1.11.tar.gz",
                       jp / "open_jtalk_dic_utf_8-1.11.tar.gz")
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == "fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a"
    with tarfile.open(archive) as tar:
        tar.extractall(jp, filter="data")
    target = OUT / "japanese"
    target.mkdir(parents=True, exist_ok=True)
    for name in ["sys.dic", "unk.dic", "char.bin", "matrix.bin", "COPYING"]:
        shutil.copyfile(jp / "open_jtalk_dic_utf_8-1.11" / name, target / name)
    manifest(target, "OpenJTalk UTF-8 dictionary 1.11; archive SHA256 fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a")

    english = DOWNLOADS / "genie-english"
    tagger = "taggers/averaged_perceptron_tagger_eng/averaged_perceptron_tagger_eng."
    names = ["checkpoint20.npz", "engdict_cache.pickle", "engdict-hot.rep", "namedict_cache.pickle",
             tagger + "tagdict.json", tagger + "weights.json", tagger + "classes.json"]
    def fetch(name):
        url = f"https://huggingface.co/High-Logic/Genie/resolve/{REVISION}/GenieData/G2P/EnglishG2P/{name}"
        print("Preparing", name, flush=True)
        return download(url, english / name)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(fetch, names))
    target = OUT / "english"
    target.mkdir(parents=True, exist_ok=True)
    with (english / "engdict_cache.pickle").open("rb") as f:
        dictionary = DataOnlyPickle(f).load()
    with (english / "namedict_cache.pickle").open("rb") as f:
        name_dictionary = DataOnlyPickle(f).load()
    for word in ["ae", "ai", "ar", "ios", "hud", "os"]:
        dictionary.pop(word, None)
    for line in (english / "engdict-hot.rep").read_text().splitlines():
        fields = line.split()
        if fields and not fields[0].startswith(";;;"):
            dictionary[fields[0].lower()] = [fields[1:]]
    for filename, data in [("dictionary.tsv", dictionary), ("names.tsv", name_dictionary)]:
        (target / filename).write_text("".join(k + "\t" + " ".join(v[0]) + "\n" for k, v in sorted(data.items())), encoding="utf-8")
    for name in ["tagdict", "weights", "classes"]:
        shutil.copyfile(english / (tagger + name + ".json"), target / ("pos-" + name + ".json"))
    with np.load(english / "checkpoint20.npz", allow_pickle=False) as weights:
        english_model(weights, "enc", target / "encoder.onnx")
        english_model(weights, "dec", target / "decoder.onnx")
    manifest(target, f"High-Logic/Genie@{REVISION}; English dictionaries, NLTK POS model, g2p_en checkpoint20")
    print("Prepared", OUT, flush=True)


if __name__ == "__main__":
    main()
