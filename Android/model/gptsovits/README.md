# GPT-SoVITS bridge

The Android application packages the GPT-SoVITS implementation from this directory. Its DEX, ONNX Runtime, JNI libraries, text frontend and audited graph templates are installed from the APK on the first start. The provider handles V2, V2 Pro, V2 Pro Plus and V5 Turbo. The checkpoint-pair importer accepts V2 Pro, V2 Pro Plus and V5 Turbo.

## Build and inspect candidates

Run these commands from the repository root with Python 3.11+, JDK 21+, SDK 37, build-tools 37.0.0, NDK 28.2.13676358 and CMake 3.22.1. For an ARM64 Release, provide the signing material expected by `Android/app/build.gradle.kts`. Model weights and language downloads are unnecessary for an APK build.

```powershell
./Android/gradlew.bat -p Android clean
./Android/gradlew.bat -p Android -PvoiceboxAbi=x86_64 :app:assembleDebug testDebugUnitTest lintDebug
./Android/gradlew.bat -p Android "-PvoiceboxOrtAar=<absolute-path-to-arm64-ORT.aar>" :app:assembleRelease lintRelease
```

The app consumes `:model:gptsovits:bundleDebugBridge` or `:model:gptsovits:bundleReleaseBridge`. Each writes the ZIP under `Android/model/gptsovits/build/outputs/bridge/<variant>/backend-packages/` and its catalog in the adjacent `bridges/` directory. Use both from the same build. The catalog records the ABI, entry, source commit, adapter digest, package SHA-256 and exact file inventory. Build release candidates from a clean, committed checkout. The stock Maven ORT supports the CPU build. Use the project's custom ARM64 AAR for its WebGPU and half-storage support. [ORT build tool](../../../tools/build_ort_webgpu.ps1) 路 [GPT runtime patches](patches)

The host pins Ming separately. Build its candidate and matching host catalog before building an APK with that revision. The boundary check reads the actual DEX class definitions, ELF ABIs, manifests, audited assets and R8 mapping. For Debug substitute the debug APK and x86_64 GPT ZIP, and omit the mapping. The Ming ZIP must match the catalog in the APK. [Ming candidate build](../ming/README.md)

```powershell
python tools/verify_backend_boundaries.py --apk Android/app/build/outputs/apk/release/app-release.apk --mapping Android/app/build/outputs/mapping/release/mapping.txt --gpt Android/model/gptsovits/build/outputs/bridge/release/backend-packages/gptsovits-arm64-v8a.zip --ming artifacts/ming-runtime-v2/ming-backend-v2-arm64-v8a.zip
```

Building and checking candidates is local. A maintainer publishes approved ZIPs at the pinned URLs in a separate release operation.

## Source and data

- `Android/app` owns Studio, the foreground service, playback/export and the HTTP/Messenger entry points. `Android/core` owns verified installation, worker binding, device facts, common audio and file operations. `Android/bridge-api` contains the parent-loaded contracts.
- `src/main/java` and `src/main/cpp` contain the GPT provider, checkpoint conversion, text frontend, inference and accelerator paths. Model metadata refers to the backend, variant and model ID. User voice IDs refer separately to their recording and transcript.
- `bridge-assets.json` pins the reviewed non-weight assets in the code package. Tools in `tools/` generate graph templates, sampling controls and optional language resources. Check the graph constants, initializers and external tensor data before accepting a new asset digest. Checkpoint manifests also pin the exact bytes of the templates copied into a voice.
- Studio downloads the official weights or imports the supported local checkpoint pairs. The external-files directories `CharacterModels/` and `RuntimeAssets/` hold model data. The internal `no_backup/model-runtimes/` directories hold verified code. The package and its capabilities can be inspected with no voice installed.
- For an original V2 voice, place the prepared export in `CharacterModels/v2/<id>/tts_models/` with the recording in the sibling `prompt_wav/` directory and `prompt_wav.json` in the voice root. Set `Normal.language` to the recording's language. The Android decoder graphs take the `sampling` input and the vocoder takes `speed`. For the Genie-TTS V2 exporter at `d347fd0`, apply the existing `sampling(model)` transformation to both decoder ONNX graphs and `speed(model)` to `vits_fp32.onnx`. Load each graph with `onnx.load(path, load_external_data=False)` and write its serialized graph back alongside the original external weights. [Graph transformations](tools/add_generation_controls.py)
- To prepare an optional standalone GPT runtime-data ZIP, install NumPy and ONNX, run `python Android/model/gptsovits/tools/prepare_language_assets.py`, then run `./Android/gradlew.bat -p Android "-PvoiceboxRuntimeAssets=<GenieData-directory>" :model:gptsovits:bundleRuntimeAssets`. The ZIP is written to this module's `build/outputs/runtime-assets/`.
- HTTP and Binder forward named scalar synthesis options to the selected backend. GPT accepts `maxDecoderSteps`, `speed`, `temperature`, `topK`, `topP` and `repetitionPenalty`. Use the same option spelling in a Messenger Bundle. Ming accepts an empty options map. See the common request identifiers in [BackendApiContract.kt](../../bridge-api/src/main/java/dev/renakoni/voicebox/bridge/BackendApiContract.kt).
