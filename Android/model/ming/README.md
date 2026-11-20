# Ming-TTS backend bridge

The Release ZIP contains **backend code only**: the package manifest, adapter/task DEX JAR, Ming JNI libraries and
their ONNX Runtime dependency, and a four-value ReLU graph for a strict WebGPU probe. It contains no model weights, speech graphs, tokenizer,
reference encoders, reference audio or voice caches. Model downloads belong to the
existing voice/model flow, using official sources or mirrors separately from Releases.

Voicebox reuses `BackendRuntime`, `GenerationRequest`, `GeneratedAudioFile`,
`GenerationContext`, `BackendApiContract`, verified downloads and the service request
gate. Ming and GPT-SoVITS use the same voice list, generation, playback, export and HTTP
API. There is no new public synthesis interface. Settings → Models shows the verified installation and offers the backend download.

## Build the backend Release asset

From the Voicebox repository on Windows:

```powershell
python Android/model/ming/build_package.py `
  --source ../ming-android `
  --ort-library ../ming-android/artifacts/tasks-arm64-v1/libonnxruntime.so `
  --ort-include ../ref/onnxruntime-webgpu/include/onnxruntime/core/session `
  --pocketfft-include ../ref/gsv.cpp/third_party `
  --output artifacts/ming-runtime-v2 `
  --catalog Android/app/src/main/assets/bridges/ming.json
```

The pinned upstream checkout is `7bba07df02d05aadafb0f2886faf98d6b5ceadfc`.
Requires the project JDK, Python 3.11+, Android platform 36, build-tools 37.0.0, NDK 28.2.13676358,
CMake 3.22.1 and `ANDROID_HOME`. The script reads only source code and the explicitly
supplied runtime library. A fixed allowlist determines ZIP contents; model directories
are never traversed. It compiles the existing `ming-android` sources without modifying
that checkout. `Tokenizer`, `TextSegments` and `TaskRequest` are reused; `Synthesis`'s
CLI launch and `System.exit` wrapper are replaced with JNI calls to the same native
entrypoints. Native code includes the checkout's current matrix checks.

The command produces a local candidate and its catalog in the same invocation. The catalog
pins the ZIP length and SHA-256, ABI, entry class, bridge contract, upstream commit and
adapter source digest. The default download URL targets the separately published
`ming-runtime-v2` release. Building the candidate does not publish that URL.
Set `--release-url` to change its release location before building.

The host reads pinned catalogs from its `bridges/` assets. The common installer downloads
and verifies each part, checks its paths and sizes, verifies the embedded manifest and
protects the DEX before atomically committing a new directory. Each revision is immutable.
Staging cleanup and publication are serialized across threads and processes.

## Runtime

The private `BackendWorkerService` executes each Ming request under the shared
`BackendRuntime` contract. The entry and upstream tasks share a package class loader;
the host supplies the contract. Metadata queries load the adapter without initializing
native libraries. Generation uses the worker's own ORT. Cancellation or completion
releases the worker before request files and links are removed. A binder death completes
a waiting request and allows a subsequent request to start a new process.

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

Stage only `ming-backend-v2-arm64-v8a.zip` in external-files `ming-release/` for
`MingRuntimeInstrumentedTest`. It verifies the pinned archive, runs the production
installer, checks real speech and reference cloning, and verifies cancellation followed
by another request. Use a separate debug application ID to preserve an existing app.
`ModelsInstrumentedTest` covers navigation and the existing creation/playback flow.
`BridgePackageTest` covers installation rollback, archive boundaries and completion checks.
