<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox 应用图标">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>Android 本地多模型语音工作站</strong><br>
  无需云端与显卡，在手机上离线运行开源语音合成与音色克隆模型
</p>

<p align="center">
  <strong>中文</strong> · <a href="README_JA.md">日本語</a> · <a href="README.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><img src="https://img.shields.io/github/v/release/Renakoni/Voicebox?style=flat-square&amp;label=release&amp;color=9A4930" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-59665A?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 11 及以上">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-655D57?style=flat-square" alt="ARM64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-6E665F?style=flat-square" alt="AGPL-3.0 许可"></a>
  <br>
  <img src="https://img.shields.io/badge/Runtime-ONNX%20Runtime%20%C2%B7%20ggml-4B6478?style=flat-square&amp;logo=onnx&amp;logoColor=white" alt="ONNX Runtime 与 ggml">
  <img src="https://img.shields.io/badge/GPU-WebGPU%20%C2%B7%20Vulkan-4D6D73?style=flat-square&amp;logo=webgpu&amp;logoColor=white" alt="WebGPU 与 Vulkan">
  <img src="https://img.shields.io/badge/NPU-Qualcomm%20QNN%20%C2%B7%20Hexagon-5C5675?style=flat-square&amp;logo=qualcomm&amp;logoColor=white" alt="Qualcomm QNN 与 Hexagon">
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><strong>下载 APK</strong></a>
  · <a href="#支持模型与计划">支持模型</a>
  · <a href="#快速上手">快速上手</a>
  · <a href="#性能与质量">性能与质量</a>
  · <a href="#从源码构建">从源码构建</a>
  · <a href="https://github.com/Renakoni/Voicebox/issues">反馈问题</a>
</p>

Voicebox 将多个主流开源语音模型带到 Android 手机上，在一个本地应用中统一提供音色克隆、声音表现控制、流式播放与外部接口调用。只需约 5 秒参考录音和对应原文，就能在手机上克隆音色。

> [!NOTE]
> 本项目对高通骁龙平台做了专门优化，使用骁龙平台以获得更完整的优化体验；至于联发科天玑，我祝他一切顺利——单纯是因为作者没钱买天玑手机，没法做针对性优化。

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>▶ 演示（33 秒，有声音）。视频里的 Roxy 语音均由小米 14 上的 Voicebox 实际生成。</sub>

## 支持模型与计划

Voicebox 采用插件化后端架构，各模型的运行库与资源包均在软件内按需独立下载和卸载，共享同一套音色库、音频输出、QQ 语音发送与本地 HTTP 接口。

| 模型 | 状态 | 说明 |
|---|---|---|
| [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) | ✅ 已支持 | 支持 V2 Pro、V2 Pro Plus 与 V5 Turbo，跨语种克隆，支持在手机上直接导入转换 `.ckpt` 与 `.pth` 权重 |
| [Ming-TTS 0.5B](https://github.com/inclusionAI/Ming-3.0-tts) | ✅ 已支持 | 内置 93 个官方音色，支持单音频克隆、情感与方言控制、朗读指令及流式播放 |
| [Qwen3-TTS-0.6B](https://github.com/QwenLM/Qwen3-TTS) | ✅ 已支持 | 官方 `0.6B-Base` Q4_K_M 权重，支持 10 种语言、零样本克隆与直接朗读、流式播放，以及在手机上直接导入转换 `.safetensors` 权重 |
| [CosyVoice3-0.5B-2512](https://github.com/FunAudioLLM/CosyVoice) | 🔧 推理已完成 | 移动端推理已跑通，待接入应用 |
| [IndexTTS-2.5](https://github.com/index-tts/index-tts) | 🗺️ 计划接入 | 适配规划中 |
| [Fish Audio S1-mini](https://github.com/fishaudio/fish-speech) | 🗺️ 计划接入 | 适配规划中 |
| [OmniVoice](https://github.com/k2-fsa/OmniVoice) | 🗺️ 计划接入 | 适配规划中 |

## 核心特性

- **多模型按需扩展**：应用本体与推理后端分离，各模型运行库和资源包可在软件内按需下载、校验或卸载，不让安装包过度膨胀。
- **全离线与硬件加速**：模型就绪后全程本地运行，无需联网与云端显卡。面向支持 GPU 加速（WebGPU / Vulkan）的移动设备设计，兼容的高通骁龙机型可进一步启用 NPU 混合加速（QNN / Hexagon HTP）。
- **零样本克隆与声音控制**：GPT-SoVITS 支持跨语种克隆，可在手机上直接导入转换原生 `.ckpt` 与 `.pth` 权重；Ming-TTS 0.5B 内置 93 个官方音色，支持单音频克隆、语速音高音量调节、情感与方言控制、朗读指令及边生成边播放；Qwen3-TTS-0.6B 支持 10 种语言、零样本克隆与无参考直接朗读、流式播放、主/子模型采样参数控制，以及在手机上直接导入转换 `.safetensors` 权重。
- **原生 QQ 语音条发送**：生成后可直接发送为 QQ 聊天中的原生可播放语音消息，支持一键对接目标会话。[查看 QQ 发送演示](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **本地 HTTP 服务**：内置轻量 `127.0.0.1` HTTP 接口，方便「阅读」等听书软件、Tasker 或其他客户端调度合成，PC 端也可通过 ADB 端口转发调用。
- **应用内更新校验**：支持检查 GitHub 最新版本，下载后自动核对文件大小、SHA-256 与包名版本。

## 快速上手

从 [Releases](https://github.com/Renakoni/Voicebox/releases/latest) 下载 **Voicebox 1.2.3**，需 Android 11 及以上的 ARM64 设备（使用 Qwen3-TTS-0.6B 需 Android 12 及以上且 GPU 支持 Vulkan 1.2 与 16 位存储缓冲区）。

> [!WARNING]
> 1.2.0 及以上版本不支持从 1.2.0 之前的旧版本覆盖安装。如仍在使用 1.2.0 之前的旧版，请先卸载再安装（1.2.0 及后续版本支持直接更新）。

1. **安装模型**：进入模型页，按需安装 **GPT-SoVITS**、**Ming-TTS 0.5B** 或 **Qwen3-TTS-0.6B**。
2. **准备音色**：进入音色页，选用官方音色，或录制、导入参考音频并填写原文来克隆音色；使用 GPT-SoVITS 时可直接导入本地 `.ckpt` 与 `.pth` 权重，使用 Qwen3-TTS-0.6B 时可直接导入 `.safetensors` 与 `config.json` 权重。
3. **生成与输出**：进入创作页，选好音色与参数，输入文字即可生成。完成后可试听、保存、分享或发送为 QQ 语音。

<details>
<summary><strong>发 QQ 语音条前的一次性设置</strong></summary>

1. 在系统「开发者选项」里打开「无线调试」。
2. 在 Voicebox 的「发送 QQ 语音」页点「连接」，按提示输入系统给出的六位配对码。配对一次即可。
3. 之后点「选择联系人并发送」，在 QQ 里打开目标会话，Voicebox 会自动完成录制占位、替换和发送。过程中请不要操作 QQ。

单条语音需短于 60 秒。

</details>

## 性能与质量

手机端基准均在 **小米 14（骁龙 8 Gen 3，16 GB RAM）** 上实测。测试沿用同一套中文短句（约 3.5–4.5 秒）与长段（约 19–24 秒）文本，使用官方权重或固定预设与随机种子；模型加载与缓存就绪后，在常温无热降频状态下，每种配置与文本长度各重复生成 **10 次**，取均值与标准差。

| 模型 | 执行路径 | 短句 RTF | 长段 RTF | ASR 转写一致率 |
|---|---|---:|---:|---:|
| GPT-SoVITS V2 Pro Plus | GPU | 0.614 | 0.556 | 99.34% |
| GPT-SoVITS V2 Pro Plus | GPU + NPU | 0.627 | 0.511 | 99.72% |
| GPT-SoVITS V5 Turbo | GPU | 2.038 | 1.816 | 99.72% |
| GPT-SoVITS V5 Turbo | GPU + NPU | 1.313 | 0.954 | 99.62% |
| Ming-TTS 0.5B | GPU | 1.821 | 1.055 | 100.00% / 100.00% |
| Ming-TTS 0.5B | GPU + NPU | 1.599 | 0.574 | 100.00% / 99.89% |
| Qwen3-TTS-0.6B | GPU | 1.105 | 0.781 | 100.00% / 100.00% |
| Qwen3-TTS-0.6B | GPU + NPU | 0.991 | 0.707 | 100.00% / 100.00% |

- **RTF**：推理耗时 ÷ 音频时长，小于 `1.0` 表示生成快于播放。
- **ASR 转写一致率**：使用离线 SenseVoice 识别生成音频，去除标点后按 `（1 − 字符编辑距离 ÷ 原文字数）× 100%` 计算。Ming-TTS 0.5B 与 Qwen3-TTS-0.6B 列中依次为短句与长段结果。
- **执行路径**：GPU 路径由 CPU 与 WebGPU（GPT-SoVITS / Ming-TTS）或 CPU 与 Vulkan（Qwen3-TTS）协同运算；GPU + NPU 路径进一步接入 Qualcomm QNN 或 Hexagon HTP 进行异构加速。

<details>
<summary><strong>GPT-SoVITS 详细基准</strong></summary>

#### 1. 手机端与 PC 端推理耗时

对照项运行[官方仓库](https://github.com/RVC-Boss/GPT-SoVITS)的 CUDA 实现，测试机配置为 RTX 3070 Ti Laptop 8 GB 显存、16 GB RAM。短句约 4 秒，长段约 22 秒，使用官方权重与同一份中文参考录音。

| 模型 | 实现 / 设备 | 短句耗时（秒） | 短句 RTF | 长段耗时（秒） | 长段 RTF |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU + NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU + NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

#### 2. 波形相对误差与转写一致率

固定输入和噪声，对比手机 GPU + NPU 与官方 PyTorch FP32 的声学输出；另用 SenseVoice 核对性能测试中的全部生成音频。

| 模型 | RMS 短句 | RMS 长段 | 一致率 · 官方 CUDA | 一致率 · 手机 GPU | 一致率 · 手机 GPU + NPU |
|---|---:|---:|---:|---:|---:|
| V2 Pro Plus | 0.2323% | 0.2351% | 99.06% | 99.34% | 99.72% |
| V5 Turbo | 1.4489% | 0.6537% | 99.25% | 99.72% | 99.62% |

- **RMS 相对误差**：`RMS(手机波形 − 官方波形) ÷ RMS(官方波形) × 100%`，越低表示计算结果越接近官方 FP32。
- 架构分析与异构调度方案详见[实现方案与优化](docs/METHODS.md)。

</details>

<details>
<summary><strong>Ming-TTS 0.5B 详细基准</strong></summary>

重复测试选用官方预设「灵小柔」，输出规格为 44.1 kHz 单声道 16-bit PCM WAV。

| 执行路径 | 文本 | 音频时长（秒） | 请求总耗时（秒） | 推理 RTF | 转写一致率 |
|---|---|---:|---:|---:|---:|
| GPU | 短句 | 3.57 ± 0.11 | 11.88 ± 0.50 | 1.821 ± 0.068 | 100.00% |
| GPU | 长段 | 19.19 ± 0.40 | 25.70 ± 1.03 | 1.055 ± 0.044 | 100.00% |
| GPU + NPU | 短句 | 3.59 ± 0.11 | 11.20 ± 0.42 | 1.599 ± 0.031 | 100.00% |
| GPU + NPU | 长段 | 19.17 ± 0.30 | 16.42 ± 0.52 | 0.574 ± 0.009 | 99.89% |

- **加速收益**：启用 NPU 加速声学生成后，长段平均 RTF 降低 **45.5%**，请求总耗时从 25.70 秒降至 16.42 秒。
- **统计口径**：推理 RTF 为核心推理耗时除以输出 WAV 时长；请求总耗时另计入应用准备、会话加载与 WAV 写入，长段中约占 5.4 秒。
- **冷启动与缓存**：冷短句在 GPU 路径下请求耗时 12.87 秒；NPU 首次建图耗时 17.53 秒并生成约 205 MB 的 QNN 缓存，后续同输入复用缓存降至 11.39 秒。
- **附加测试**：测试生成的所有 WAV 均可正常解码且无削波失真；NPU 路径下的中文克隆与英文预设抽检转写一致率均为 100.00%。

</details>

<details>
<summary><strong>Qwen3-TTS-0.6B 详细基准</strong></summary>

使用 Voicebox 1.2.3 正式版、正式千问 v6 桥接与官方 `Qwen3-TTS-0.6B-Base Q4_K_M` 权重，在前台亮屏状态下使用同一份 4.325 秒中文参考录音进行 ICL 音色克隆（`seed 42`，启用采样），输出 24 kHz 单声道 float32 WAV。

| 执行路径 | 文本 | 音频时长（秒） | 请求总耗时（秒） | 核心推理耗时（秒） | 首段 PCM（秒） | 推理 RTF | 转写一致率 |
|---|---|---:|---:|---:|---:|---:|---:|
| GPU | 短句 | 4.48 ± 0.00 | 5.03 ± 0.03 | 4.95 ± 0.03 | 1.50 ± 0.01 | 1.105 ± 0.006 | 100.00% |
| GPU | 长段 | 22.00 ± 0.00 | 17.31 ± 0.17 | 17.19 ± 0.18 | 1.50 ± 0.01 | 0.781 ± 0.008 | 100.00% |
| GPU + NPU | 短句 | 4.24 ± 0.00 | 4.26 ± 0.07 | 4.20 ± 0.07 | 1.48 ± 0.01 | 0.991 ± 0.017 | 100.00% |
| GPU + NPU | 长段 | 23.68 ± 0.00 | 16.85 ± 0.62 | 16.74 ± 0.62 | 1.48 ± 0.01 | 0.707 ± 0.026 | 100.00% |

- **后端与 NPU 收益**：Talker 运行于 4 个 CPU 线程（`int8mm` 内核），codec 使用 Vulkan（`Vulkan0`），GPU + NPU 路径进一步将 DAC 卸载至 Hexagon v75（`HTP0`）。启用 NPU 后，短句推理 RTF 由 `1.105` 降至 `0.991`（快于实时播放），长段 RTF 降低 **9.5%**（`0.781 → 0.707`）。
- **v6 桥接对比 1.2.2 / v5**：同机同输入下，v6 使推理 RTF 分别降低 **11.2%**（GPU 短句）、**9.6%**（GPU 长段）、**14.9%**（GPU + NPU 短句）与 **15.5%**（GPU + NPU 长段），四组测试 10/10 与 v5 逐采样完全一致。
- **首次克隆与直接朗读**：单次短句抽检中，新进程首次克隆（复用磁盘缓存）在 GPU / GPU + NPU 路径下总耗时分别为 `10.89` 秒 / `7.87` 秒（加载 `1.148` 秒 / `1.067` 秒，首段 PCM `4.946` 秒 / `3.559` 秒，RTF `1.889` / `1.498`）；热态无参考直接朗读首段 PCM 仅 `0.320` 秒 / `0.307` 秒，总耗时 `4.61` 秒 / `3.76` 秒，RTF `0.799` / `0.651`。
- **附加校验**：全部 44 个生成 WAV（40 次热态克隆 + 4 次单次测试）SenseVoice 转写编辑距离均为 `0`（一致率 `100.00%`），最大峰值 `0.5474` 且满幅削波点为 `0`，测试期间皮温 `31.8–43.7 °C`，系统热状态始终为 `0`。

</details>

## 从源码构建

需要 JDK 21、Android SDK 37（含 NDK、CMake）和 Python 3.12+。

```powershell
git clone https://github.com/Renakoni/Voicebox.git
cd Voicebox/Android
.\gradlew.bat :app:assembleDebug
```

`:app:assembleDebug` 构建应用本体，各模型的推理后端按需在软件内从 Release 下载。如需自行打包带 WebGPU 加速的 GPT-SoVITS 后端，请先构建定制 ONNX Runtime（[构建脚本](tools/build_ort_webgpu.ps1)、[补丁](Android/model/gptsovits/patches)），并在执行 `:model:gptsovits:bundleDebugBridge` 或 `:model:gptsovits:bundleReleaseBridge` 时通过 `-PvoiceboxOrtAar=<AAR路径>` 传入；不传该参数时默认打包 CPU 推理版本。

## 问题反馈

欢迎提交 [Issue](https://github.com/Renakoni/Voicebox/issues) 或 [Pull Request](https://github.com/Renakoni/Voicebox/pulls)。反馈问题请附应用版本、手机型号、使用的模型与复现步骤。

## 致谢与许可

感谢以下开源项目与作者：

- 模型与推理生态：[GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)、[Ming-3.0-tts](https://github.com/inclusionAI/Ming-3.0-tts)、[Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS)、[ggml](https://github.com/ggml-org/ggml)、[Genie-TTS](https://github.com/High-Logic/Genie-TTS)、[RinStel Android](https://github.com/RinStel/Genie-TTS-Android) 与 [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp)。
- 界面与组件参考：[Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android) 与 [NextVol](licenses/NextVol-Apache-2.0.txt)。
- 演示视频中的洛琪希（Roxy）音色来自 [Shinowing](https://github.com/Shinowing) 的 [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model)，感谢作者分享模型。

Voicebox 的原创代码与修改采用 [GNU AGPL-3.0](LICENSE) 许可。

第三方组件保留各自的版权与许可证，见 [licenses](licenses)、[应用](Android/app/src/main/cpp/third_party)、[公共音频](Android/core/src/main/cpp/third_party)、[GPT-SoVITS](Android/model/gptsovits/src/main/cpp/third_party) 的原生依赖目录，以及应用和桥接包中的 `*-licenses` 资产。Release 附带相关依赖的对应源码。
