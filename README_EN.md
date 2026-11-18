<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox app icon">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>GPT-SoVITS in your pocket.</strong><br>
  High-quality on-device voice cloning for Android, no cloud or external GPUs required.<br>
  <a href="README.md">中文</a> · <a href="README_JA.md">日本語</a> · <a href="README_EN.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><img src="https://img.shields.io/github/v/release/Renakoni/Voicebox?style=flat-square&amp;label=release&amp;color=9A4930" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-59665A?style=flat-square" alt="Android 11+">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-655D57?style=flat-square" alt="ARM64">
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><strong>Download APK</strong></a>
  · <a href="#getting-started">Getting Started</a>
  · <a href="#performance">Performance</a>
  · <a href="#building-from-source">Build</a>
  · <a href="https://github.com/Renakoni/Voicebox/issues">Issues</a>
</p>

Clone a voice with just ~5 seconds of reference audio and its transcript.

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>Demo (33 seconds, with sound and Chinese captions). Voicebox generated every Roxy line on a Xiaomi 14.</sub>

## Features

- **Fully On-Device and Offline**: Once models are downloaded, speech generation runs entirely on your phone with zero network requests or cloud dependency. Powered by WebGPU, with optional NPU hybrid acceleration on compatible Snapdragon devices.
- **Cross-Lingual Voice Cloning**: Reference audio and target generation languages are completely decoupled, enabling cross-lingual voice transfer (e.g., clone a voice from a Japanese clip and speak Chinese or English).
- **Direct GPT-SoVITS Weight Import**: Import `.ckpt` and `.pth` checkpoint files directly and convert them on-device, supporting V2 Pro, V2 Pro Plus, and V5 Turbo.
- **Native QQ Voice Messages**: Send generated audio directly as native, tap-to-play QQ voice messages (not audio files or screen recordings). [View QQ demo (14 seconds)](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **Local HTTP Server**: Built-in lightweight HTTP API for third-party clients like Tasker or audiobook readers, with PC access supported via ADB port forwarding.

## Getting started

Download **Voicebox 1.1.2** (72.6 MiB) from [Releases](https://github.com/Renakoni/Voicebox/releases/latest). It requires an **ARM64 device running Android 11 or later** and can be installed over earlier official releases.

1. On the **音色** (Voices) tab, download the official V2 Pro Plus or V5 Turbo model. Voicebox downloads the shared resources with it. To use your own weights, tap **导入音色** (Import voice) and choose the `.ckpt` and `.pth` files.
2. Choose a base model. Press and hold to record a reference clip, or select an existing one. Enter its transcript, choose its language, and save.
3. On **创作** (Create), choose the voice and output language, enter your text, and tap **生成语音** (Generate speech). You can then play, save, share, or send the audio to QQ.

<details>
<summary><strong>One-time setup for QQ voice messages</strong></summary>

1. Turn on **Wireless debugging** in Android's developer options.
2. On the **发送 QQ 语音** page, tap **连接** and enter the six-digit pairing code shown by Android. You only need to pair once.
3. Tap **选择联系人并发送** and open the chat in QQ. Voicebox records a temporary voice message, replaces it with your audio, and sends it. Leave QQ alone until it finishes.

Each message must be shorter than 60 seconds.

</details>

## Performance

> **TL;DR**: On a Snapdragon 8 Gen 3, V2 Pro Plus synthesizes a 4-second sentence in ~**2.6 seconds**; for long passages, the RTF reaches **0.51** (~2× faster than real-time playback). With NPU acceleration enabled, V5 Turbo achieves an RTF of **0.95** on long passages, also running faster than real-time.

For both test passages, we used official model weights and the same Chinese reference audio. The short sentence produced ~**4 seconds** of audio, and the long passage ~**22 seconds**. After model loading and cache preparation, each passage was generated **10 times per configuration**. Values are reported as **mean ± standard deviation**.

Comparative GPT-SoVITS benchmarks use the [official repository](https://github.com/RVC-Boss/GPT-SoVITS) implementation on a laptop with an **Intel i9-12900H, RTX 3070 Ti Laptop (8 GB VRAM), and 16 GB RAM**. All Voicebox benchmarks were measured on a **Xiaomi 14 (Snapdragon 8 Gen 3, 16 GB RAM)**.

| Model | Implementation / device | Short time (s) | Short RTF | Long time (s) | Long RTF |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · i9-12900H | 7.008 ± 0.950 | 1.752 ± 0.249 | 39.173 ± 1.097 | 1.731 ± 0.030 |
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU + NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · i9-12900H | 12.660 ± 0.859 | 3.162 ± 0.209 | 71.061 ± 3.277 | 3.137 ± 0.049 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU + NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

- **RTF (Real-Time Factor)** = Generation time ÷ Audio duration. A value below 1.0 indicates generation is faster than real-time playback.
- **Hardware backends**: Phone GPU path utilizes CPU + WebGPU; GPU + NPU path leverages Qualcomm QNN for hardware-level acceleration (requires compatible runtime libraries), falling back to CPU when acceleration is unavailable.
- For architectural differences and hardware scheduling design, see [Architecture and Optimizations](docs/METHODS_EN.md).

## Generation Quality

With identical inputs and noise, the V2 Pro Plus waveform on the phone differed from the official PyTorch FP32 output by about **0.23% RMS**. For the two test passages, transcription agreement with the original text was above **99%** in each phone configuration.

We measured waveform error on the GPU + NPU path using one short and one long set of fixed inputs. Separately, SenseVoice transcribed all 160 clips from the performance tests, with 10 short and 10 long clips per configuration.

| Model | RMS short | RMS long | ASR · official CPU | ASR · official CUDA | ASR · phone GPU | ASR · phone GPU + NPU |
|---|---:|---:|---:|---:|---:|---:|
| V2 Pro Plus | 0.2323% | 0.2351% | 98.87% | 99.06% | 99.34% | 99.72% |
| V5 Turbo | 1.4489% | 0.6537% | 99.53% | 99.25% | 99.72% | 99.62% |

- **Relative RMS error** is `RMS(phone − official) ÷ RMS(official) × 100%`. Lower values mean the waveforms are closer.
- **ASR transcription agreement** is `(1 − character edit distance ÷ reference length) × 100%` after removing punctuation. It measures how closely the transcript matches the input text.

## Building from source

Requires JDK 21, Android SDK 37 (with NDK and CMake) and Python 3.12+.

```powershell
git clone https://github.com/Renakoni/Voicebox.git
cd Voicebox
python -m pip install numpy onnx
python tools/prepare_language_assets.py
cd Android
.\gradlew.bat :app:assembleDebug
```

The default build uses the CPU for inference. To enable WebGPU, build the custom ONNX Runtime and pass its AAR to Gradle with `-PgenieOrtAar=<path-to-AAR>`. [Build script](tools/build_ort_webgpu.ps1) · [Patches](patches)

## Feedback and contributing

[Issues](https://github.com/Renakoni/Voicebox/issues) and [pull requests](https://github.com/Renakoni/Voicebox/pulls) are welcome. For bug reports, include the app version, phone model, voice model, and steps to reproduce the problem.

## Credits and license

Voicebox builds on [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS), [Genie-TTS](https://github.com/High-Logic/Genie-TTS) and [RinStel Android](https://github.com/RinStel/Genie-TTS-Android). Mobile optimizations drew on [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp), and some UI components are adapted from [Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android).

The Roxy (洛琪希) voice in the demo videos comes from [Shinowing](https://github.com/Shinowing)'s [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model). Thank you for sharing the model.

Voicebox's original code and modifications are licensed under [GNU AGPL-3.0](LICENSE).

Third-party components retain their own copyrights and licenses; see [licenses](licenses), the [native dependency directory](Android/app/src/main/cpp/third_party) and the `*-licenses` directories in the app assets. Releases include the corresponding source for the relevant dependencies.
