"""Stage a signed ARM64 release APK and its GitHub Latest update manifest."""

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="directory for release assets")
    args = parser.parse_args()
    release = Path(__file__).resolve().parents[1] / "Android/app/build/outputs/apk/release"
    metadata = json.loads((release / "output-metadata.json").read_bytes())
    if metadata["applicationId"] != "dev.renakoni.voicebox" or metadata["variantName"] != "release":
        raise ValueError("Expected the production VoiceBox release")
    build, = metadata["elements"]
    version = build["versionName"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Expected a stable major.minor.patch version")
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / f"Voicebox-{version}-arm64.apk"
    shutil.copyfile(release / build["outputFile"], apk)
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest = dict(versionName=version, versionCode=build["versionCode"],
                    bytes=apk.stat().st_size, sha256=digest)
    (args.output / "voicebox-update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared app update for v{version} (versionCode {build['versionCode']})")


if __name__ == "__main__":
    main()
