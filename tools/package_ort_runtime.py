"""Replace the arm64 C API library in a matching official ORT 1.27.0 AAR.

Keeps the official Java classes, JNI binding, licenses and other entries intact.
The caller must verify the resulting runtime on device before deployment.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    library = args.library.read_bytes()
    if library[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", library, 18)[0] != 183:
        parser.error("Expected a little-endian ELF64 arm64 library")
    entry = "jni/arm64-v8a/libonnxruntime.so"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.aar) as source:
        source.getinfo(entry)
        with ZipFile(args.output, "x") as destination:
            for item in source.infolist():
                destination.writestr(item, library if item.filename == entry else source.read(item))
    report = dict(source_aar=str(args.aar), library=str(args.library), output=str(args.output),
                  source_aar_sha256=hashlib.sha256(args.aar.read_bytes()).hexdigest(),
                  library_sha256=hashlib.sha256(library).hexdigest(),
                  output_sha256=hashlib.sha256(args.output.read_bytes()).hexdigest())
    args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
