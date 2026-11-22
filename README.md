<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox app icon">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>On-device multi-model speech workstation for Android</strong><br>
  Run open-source TTS and voice cloning models offline on your phone, with no cloud or desktop GPU required
</p>

<p align="center">
  <a href="README_ZH.md">中文</a> · <a href="README_JA.md">日本語</a> · <strong>English</strong>
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><img src="https://img.shields.io/github/v/release/Renakoni/Voicebox?style=flat-square&amp;label=release&amp;color=9A4930" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-59665A?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-655D57?style=flat-square" alt="ARM64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-6E665F?style=flat-square" alt="AGPL-3.0 License"></a>
  <br>
  <img src="https://img.shields.io/badge/Runtime-ONNX%20Runtime-4B6478?style=flat-square&amp;logo=onnx&amp;logoColor=white" alt="ONNX Runtime">
  <img src="https://img.shields.io/badge/GPU-WebGPU-4D6D73?style=flat-square&amp;logo=webgpu&amp;logoColor=white" alt="WebGPU">
  <img src="https://img.shields.io/badge/NPU-Qualcomm%20QNN-5C5675?style=flat-square&amp;logo=qualcomm&amp;logoColor=white" alt="Qualcomm QNN">
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><strong>Download APK</strong></a>
  · <a href="#models-and-roadmap">Models</a>
  · <a href="#getting-started">Getting Started</a>
  · <a href="#performance-and-quality">Performance</a>
  · <a href="#building-from-source">Build</a>
  · <a href="https://github.com/Renakoni/Voicebox/issues">Issues</a>
</p>

Voicebox brings multiple open-source speech models to Android in one offline app, combining voice cloning, delivery controls, streaming playback, and a local API. Clone a voice with roughly 5 seconds of reference audio and its transcript.

> [!NOTE]
> Voicebox is specifically tuned for Qualcomm Snapdragon chips; use a Snapdragon device for the full experience. As for MediaTek Dimensity—godspeed. I simply can't afford a Dimensity phone to optimize for it.

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>▶ Demo (33 seconds, with sound and Chinese captions). Every Roxy line in the video was generated on a Xiaomi 14 using Voicebox.</sub>

## Models and Roadmap

Voicebox uses a modular backend architecture. Each model runtime and resource pack is downloaded and uninstalled independently inside the app, sharing the same voice library, audio export, QQ voice sending, and local HTTP API.

| Model | Status | Highlights |
|---|---|---|
| [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) | ✅ Supported | V2 Pro, V2 Pro Plus, and V5 Turbo; cross-lingual cloning and on-device `.ckpt` / `.pth` conversion |
| [Ming-TTS 0.5B](https://github.com/inclusionAI/Ming-3.0-tts) | ✅ Supported | 93 built-in official voices, single-clip cloning, emotion and dialect controls, reading instructions, and streaming playback |
| [CosyVoice3-0.5B-2512](https://github.com/FunAudioLLM/CosyVoice) | 🔧 Inference Ready | Mobile inference verified; app integration pending |
| [Qwen3-TTS-0.6B-Base](https://github.com/QwenLM/Qwen3-TTS) | 🔧 Inference Ready | Mobile inference verified; app integration pending |
| [IndexTTS-2.5](https://github.com/index-tts/index-tts) | 🗺️ Planned | Planned for mobile support |
| [Fish Audio S1-mini](https://github.com/fishaudio/fish-speech) | 🗺️ Planned | Planned for mobile support |
| [OmniVoice](https://github.com/k2-fsa/OmniVoice) | 🗺️ Planned | Planned for mobile support |

## Features

- **On-demand model packs**: The app and inference backends are decoupled. Download, verify, or uninstall individual model runtimes and packs on demand inside the app without bloating the APK.
- **Offline inference with hardware acceleration**: Everything runs locally once models are ready, with no network calls or cloud GPUs. Built for GPU-accelerated mobile devices, with additional Qualcomm QNN NPU hybrid acceleration on compatible Snapdragon phones.
- **Zero-shot cloning and expressive controls**: GPT-SoVITS supports cross-lingual voice transfer and direct on-device import of native `.ckpt` and `.pth` checkpoints; Ming-TTS 0.5B includes 93 official voices, single-clip cloning, speed/pitch/volume controls, emotions, dialects, natural-language reading instructions, and real-time streaming playback.
- **Native QQ voice messages**: Send generated audio directly as native, tap-to-play QQ voice messages rather than audio attachments. [Watch QQ demo](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **Local HTTP API**: Lightweight `127.0.0.1` server for audiobook readers, Tasker, or other clients, with desktop access supported via ADB port forwarding.
- **Verified in-app updates**: Check GitHub for new releases inside the app, with automatic verification of file size, SHA-256, package name, and version.

## Getting Started

Download **Voicebox 1.2.0** from [Releases](https://github.com/Renakoni/Voicebox/releases/latest) (requires an ARM64 device running Android 11 or later).

> [!WARNING]
> 1.2.0 is a breaking release and cannot be installed over earlier versions. Uninstall any previous version before installing 1.2.0 (future releases will update in place).

1. **Install models**: Open **Models** and install **GPT-SoVITS** or **Ming-TTS 0.5B** as needed.
2. **Set up voices**: Open **Voices** to pick an official voice, or record/import a reference clip with its transcript to clone a voice. For GPT-SoVITS, you can also import local `.ckpt` and `.pth` weights directly.
3. **Create and export**: Open **Create**, pick a voice and adjust your settings, enter text, and generate. Play, save, share, or send the result to QQ.

<details>
<summary><strong>One-time setup for QQ voice messages</strong></summary>

1. Turn on **Wireless debugging** in Android's Developer options.
2. On the **Send to QQ** screen, tap **Connect** and enter the six-digit pairing code shown by Android. Pairing is only needed once.
3. Tap **Choose contact and send**, then open the target chat in QQ. Voicebox will record a placeholder voice message, replace its audio, and send it. Leave QQ untouched until it finishes.

Each message must be shorter than 60 seconds.

</details>

## Performance and Quality

All mobile benchmarks were measured on a **Xiaomi 14 (Snapdragon 8 Gen 3, 16 GB RAM)** using the same Chinese short sentence (~3.5–4 s) and long passage (~19–22 s), with official weights or fixed presets and seeds. Once models and caches were warm, each configuration and length ran **10 times** at normal ambient temperature with no thermal throttling; values are reported as **mean ± standard deviation**.

| Model | Execution Path | Short RTF | Long RTF | ASR Agreement |
|---|---|---:|---:|---:|
| GPT-SoVITS V2 Pro Plus | GPU | 0.614 | 0.556 | 99.34% |
| GPT-SoVITS V2 Pro Plus | GPU + NPU | 0.627 | 0.511 | 99.72% |
| GPT-SoVITS V5 Turbo | GPU | 2.038 | 1.816 | 99.72% |
| GPT-SoVITS V5 Turbo | GPU + NPU | 1.313 | 0.954 | 99.62% |
| Ming-TTS 0.5B | GPU | 1.821 | 1.055 | 100.00% / 100.00% |
| Ming-TTS 0.5B | GPU + NPU | 1.599 | 0.574 | 100.00% / 99.89% |

- **RTF (Real-Time Factor)**: Inference time ÷ audio duration. Values below `1.0` run faster than real-time playback.
- **ASR agreement**: Transcribed offline with SenseVoice and scored after stripping punctuation as `(1 − character edit distance ÷ reference length) × 100%`. For Ming-TTS 0.5B, values show short / long results respectively.
- **Execution paths**: GPU runs on CPU + WebGPU; GPU + NPU adds Qualcomm QNN heterogeneous acceleration.

<details>
<summary><strong>GPT-SoVITS detailed benchmarks</strong></summary>

#### 1. Mobile vs. desktop GPU latency

The desktop baseline runs the [official repository](https://github.com/RVC-Boss/GPT-SoVITS) CUDA implementation on an RTX 3070 Ti Laptop (8 GB VRAM, 16 GB RAM), using official weights and the same Chinese reference clip (~4 s short sentence, ~22 s long passage).

| Model | Implementation / Device | Short time (s) | Short RTF | Long time (s) | Long RTF |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU + NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU + NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

#### 2. Waveform error and transcription accuracy

Waveform error compares phone GPU + NPU output against official PyTorch FP32 under identical inputs and noise. SenseVoice transcribed all clips generated in the latency runs.

| Model | RMS Short | RMS Long | ASR · Official CUDA | ASR · Phone GPU | ASR · Phone GPU + NPU |
|---|---:|---:|---:|---:|---:|
| V2 Pro Plus | 0.2323% | 0.2351% | 99.06% | 99.34% | 99.72% |
| V5 Turbo | 1.4489% | 0.6537% | 99.25% | 99.72% | 99.62% |

- **Relative RMS error**: `RMS(phone − official) ÷ RMS(official) × 100%`; lower means closer numerical match to FP32.
- See [Architecture and Optimizations](docs/METHODS_EN.md) for model analysis and scheduling details.

</details>

<details>
<summary><strong>Ming-TTS 0.5B detailed benchmarks</strong></summary>

Repeated runs used the official Chinese preset `灵小柔`, outputting 44.1 kHz mono 16-bit PCM WAV.

| Execution Path | Passage | Audio Length (s) | End-to-End Time (s) | Inference RTF | ASR Agreement |
|---|---|---:|---:|---:|---:|
| GPU | Short | 3.57 ± 0.11 | 11.88 ± 0.50 | 1.821 ± 0.068 | 100.00% |
| GPU | Long | 19.19 ± 0.40 | 25.70 ± 1.03 | 1.055 ± 0.044 | 100.00% |
| GPU + NPU | Short | 3.59 ± 0.11 | 11.20 ± 0.42 | 1.599 ± 0.031 | 100.00% |
| GPU + NPU | Long | 19.17 ± 0.30 | 16.42 ± 0.52 | 0.574 ± 0.009 | 99.89% |

- **NPU speedup**: Accelerating acoustic synthesis via QNN reduces long-passage RTF by **45.5%** and cuts end-to-end latency from 25.70 s to 16.42 s.
- **Timing breakdown**: Inference RTF divides core synthesis time by output WAV duration. End-to-end time also includes app preparation, session setup, and WAV export (~5.4 s on long passages).
- **Cold start and cache**: Cold short-sentence requests take 12.87 s on GPU. First-time NPU graph compilation takes 17.53 s and caches a ~205 MB QNN context; subsequent runs reuse the cache and drop to 11.39 s.
- **Additional checks**: All generated WAV files decoded cleanly with zero clipping; NPU checks for Chinese voice cloning and English presets both reached 100.00% ASR agreement.

</details>

## Building from Source

Requires JDK 21, Android SDK 37 (with NDK and CMake), and Python 3.12+.

```powershell
git clone https://github.com/Renakoni/Voicebox.git
cd Voicebox/Android
.\gradlew.bat :app:assembleDebug
```

The default build runs on CPU. To enable WebGPU acceleration, build the custom ONNX Runtime ([build script](tools/build_ort_webgpu.ps1), [patches](Android/model/gptsovits/patches)) and pass its AAR to Gradle via `-PvoiceboxOrtAar=<path-to-AAR>`.

## Feedback and Contributing

[Issues](https://github.com/Renakoni/Voicebox/issues) and [pull requests](https://github.com/Renakoni/Voicebox/pulls) are welcome. When reporting a bug, please include the app version, phone model, TTS model used, and steps to reproduce.

## Credits and License

Special thanks to the open-source projects and authors behind Voicebox:

- Models and inference: [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS), [Ming-3.0-tts](https://github.com/inclusionAI/Ming-3.0-tts), [Genie-TTS](https://github.com/High-Logic/Genie-TTS), [RinStel Android](https://github.com/RinStel/Genie-TTS-Android), and [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp).
- UI and component references: [Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android) and [NextVol](licenses/NextVol-Apache-2.0.txt).
- The Roxy voice in the demo video comes from [Shinowing](https://github.com/Shinowing)'s [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model). Thank you for sharing it.

Voicebox's original code and modifications are licensed under [GNU AGPL-3.0](LICENSE).

Third-party components retain their own copyrights and licenses; see [licenses](licenses), the native dependencies in [app](Android/app/src/main/cpp/third_party), [core](Android/core/src/main/cpp/third_party), and [GPT-SoVITS](Android/model/gptsovits/src/main/cpp/third_party), and the `*-licenses` assets in the app and bridge packages. Releases include the corresponding source for relevant dependencies.
