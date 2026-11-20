# Ming-TTS backend bridge

The Release ZIP contains **backend code only**: the task JAR, Ming JNI libraries and
their ONNX Runtime dependency. It contains no model weights, ONNX graphs, tokenizer,
reference encoders, reference audio or voice caches. Model downloads belong to the
existing voice/model flow, using official sources or mirrors separately from Releases.

Voicebox reuses `BackendRuntime`, `GenerationRequest`, `GeneratedAudioFile`,
`GenerationContext`, `BackendApiContract`, verified downloads and the service request
gate. Ming and GPT-SoVITS use the same voice list, generation, playback, export and HTTP
API. There is no new public synthesis interface. The Settings → More models page only
downloads the backend.

## Build the backend Release asset

From the Voicebox repository on Windows:

```powershell
python Android/model/ming/build_package.py `
  --source ../ming-android `
  --ort-library ../ming-android/artifacts/tasks-arm64-v1/libonnxruntime.so `
  --ort-include ../ref/onnxruntime-webgpu/include/onnxruntime/core/session `
  --pocketfft-include ../ref/gsv.cpp/third_party `
  --output artifacts/ming-runtime-v1 `
  --catalog Android/model/ming/src/main/assets/ming-runtime.json
```

Requires JDK 11+, Python 3.11+, Android platform/build-tools 36, NDK 28.2.13676358,
CMake 3.22.1 and `ANDROID_HOME`. The script reads only source code and the explicitly
supplied runtime library. A fixed allowlist determines ZIP contents; model directories
are never traversed. It compiles the existing `ming-android` sources without modifying
that checkout. `Tokenizer`, `TextSegments` and `TaskRequest` are reused; `Synthesis`'s
CLI launch and `System.exit` wrapper are replaced with JNI calls to the same native
entrypoints. Native code includes the checkout's current matrix checks.

Publish `ming-backend-v1-arm64-v8a.zip` to the `ming-runtime-v1` GitHub Release and
commit the generated `ming-runtime.json` with the app. The catalog pins the archive
length, SHA-256, ABI and source commit. Use `--release-url` for another release.
Building does not upload anything. The app's APK contains only the adapter and catalog;
the downloaded task JAR and native libraries stay outside its Gradle source sets.

The installer verifies the downloaded archive before extracting listed paths into a
private staging directory. Duplicate paths, traversal and file-size mismatches fail.
DEX is made read-only before loading on Android 14+. Only the completed installation
is made available to the adapter; failed downloads preserve previous versions.

## Runtime

The non-exported `MingWorkerService` loads the downloaded JAR and libraries in the
application's private `:ming` process. The host's foreground inference service owns
the request. Cancellation releases this process and its native allocations. Ming's
ORT and environment do not share a process with GPT-SoVITS's runtime. Production
requires neither shell execution nor root/ADB.

Model data lives under external-files `CharacterModels/ming/dense-0.5b/`, alongside
the existing character-model tree. A temporary directory links the installed backend
and the independently supplied model data into the upstream bundle layout. Cleanup
does not follow these links into the voice library.

This integration adapts ordinary TTS and single-reference cloning to the existing
generation request, producing 44.1 kHz mono WAV. Existing audio decoding also accepts
compressed references. The task package's CPU and WebGPU audio paths use batch 4;
GPT-specific sampling controls do not apply. The separately validated fixed-voice
dynamic-batch-8/QNN configuration and additional task controls are not enabled here.

## Local verification

For tests, push the existing Ming experiment's model data **separately**, without a
Release ZIP, to `CharacterModels/ming/dense-0.5b/`: its `models/`, `reference/`, `voice/`
directories and `examples/reference.wav`. These are the official model's already
exported Android test resources. The bridge consumes these exported graphs and data;
direct loading/conversion of the original official checkpoint is not added in this
backend-only phase.

Stage only `ming-backend-v1-arm64-v8a.zip` in external-files `ming-release/` for
`MingRuntimeInstrumentedTest`. It verifies the pinned archive, runs the production
installer, checks real speech and reference cloning, and verifies cancellation followed
by another request. Use a separate debug application ID to preserve an existing app.
`MoreModelsInstrumentedTest` covers navigation and the existing creation/playback flow.
`MingPackageTest` covers installation rollback, archive boundaries and completion checks.
