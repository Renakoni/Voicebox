<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox 应用图标">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>把 GPT-SoVITS 装进口袋。</strong><br>
  无需云端与显卡，在 Android 本地实现高质量语音克隆
</p>

<p align="center">
  <strong>中文</strong> · <a href="README_JA.md">日本語</a> · <a href="README.md">English</a>
</p>


<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><img src="https://img.shields.io/github/v/release/Renakoni/Voicebox?style=flat-square&amp;label=release&amp;color=9A4930" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-59665A?style=flat-square" alt="Android 11 及以上">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-655D57?style=flat-square" alt="ARM64">
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><strong>下载 APK</strong></a>
  · <a href="#快速上手">快速上手</a>
  · <a href="#推理性能">性能</a>
  · <a href="#从源码构建">从源码构建</a>
  · <a href="https://github.com/Renakoni/Voicebox/issues">反馈问题</a>
</p>

用约 5 秒的参考录音和对应原文，就能在手机上克隆音色。

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>▶ 演示（33 秒，有声音）。视频里的 Roxy 语音均由小米 14 上的 Voicebox 实际生成。</sub>



## 核心特性

- **全链路端侧离线**：模型下载完成后，推理全程在本地执行，无需联网与外部显卡。计算由 WebGPU 驱动，兼容的高通设备可启用 NPU 混合加速。
- **跨语种克隆合成**：参考录音与生成语言完全解耦，支持跨语种音色迁移（例如使用日语参考音频合成中文或英文）。
- **原生权重直接导入**：支持直接载入 GPT-SoVITS 训练产出的 `.ckpt` 与 `.pth` 权重，并在端侧一键转换为可用模型，覆盖 V2 Pro、V2 Pro Plus 与 V5 Turbo。
- **原生 QQ 语音条发送**：生成后可直接发送为 QQ 聊天中的原生语音消息（非文件或录屏播放），支持一键对接目标会话。[查看 QQ 发送演示（14 秒）](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **本地 HTTP 服务**：内置轻量 API 服务，方便「阅读」等听书软件、Tasker 或其他客户端直接调度手机合成语音，PC 端亦可通过 ADB 端口转发调用。

## 快速上手

从 [Releases](https://github.com/Renakoni/Voicebox/releases/latest) 下载 **Voicebox 1.1.3**（72.7 MiB）。

1. **下载模型**：在「音色」页下载官方 V2 Pro Plus 或 V5 Turbo，应用会一并准备所需资源。也可以点「导入音色」，选自己的 `.ckpt` 与 `.pth`。
2. **克隆音色**：选一个基础模型，长按录音或选择一段参考音频，填写对应原文和参考语言，保存。
3. **生成语音**：回到「创作」，选音色和生成语言，输入文字，点「生成语音」。完成后可以试听、保存、分享，或者发到 QQ。

<details>
<summary><strong>发 QQ 语音条前要做的一次性设置</strong></summary>

1. 在系统「开发者选项」里打开「无线调试」。
2. 在 Voicebox 的「发送 QQ 语音」页点「连接」，按提示输入系统给出的六位配对码。配对一次即可。
3. 之后点「选择联系人并发送」，在 QQ 里打开目标会话，Voicebox 会自动完成录制占位、替换和发送。过程中请不要操作 QQ。

单条语音需短于 60 秒。

</details>

## 推理性能

> **省流**：在骁龙 8 Gen 3上，V2 Pro Plus 生成一句 4 秒的话约需 **2.6 秒**；长段落 RTF 约 **0.51**，生成速度大约是播放速度的两倍。V5 Turbo 开启 NPU 后长段 RTF 降到 **0.95**，同样快于播放。

下表中，短句约 **4 秒**、长段约 **22 秒**音频，使用官方权重和同一份中文参考录音。模型加载与缓存准备完成后，每种配置、每种长度生成 **10 次**，结果为**均值 ± 标准差**。

参与比较的 GPT-SoVITS 使用[官方仓库](https://github.com/RVC-Boss/GPT-SoVITS)的模型与实现，测试电脑为 **i9-12900H、RTX 3070 Ti Laptop（8 GB 显存）、16 GB RAM**。Voicebox 的测试设备为 **小米 14（骁龙 8 Gen 3、16 GB RAM）**。

| 模型 | 实现 / 设备 | 短句耗时（秒） | 短句 RTF | 长段耗时（秒） | 长段 RTF |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · i9-12900H | 7.008 ± 0.950 | 1.752 ± 0.249 | 39.173 ± 1.097 | 1.731 ± 0.030 |
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU＋NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · i9-12900H | 12.660 ± 0.859 | 3.162 ± 0.209 | 71.061 ± 3.277 | 3.137 ± 0.049 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU＋NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

- **RTF（实时率）** = 生成耗时 ÷ 音频时长，小于 1 表示生成速度快于实际播放速度。
- **硬件后端说明**：手机 GPU 路径采用 CPU＋WebGPU 协同运算；GPU＋NPU 路径接入 Qualcomm QNN 引擎进行异构加速（依赖兼容运行库），未启用加速时自动回退至 CPU 推理。
- 两种模型的计算特性与异构调度方案，详见[实现方案与优化](docs/METHODS.md)。

## 生成质量

固定输入和噪声后，V2 Pro Plus 在手机上的波形与官方 PyTorch FP32 输出相差约 **0.23% RMS**。在本次短句和长段测试中，手机生成音频的转写一致率均高于 **99%**。

波形对照固定输入和噪声，比较 GPU＋NPU 与官方 PyTorch FP32 的声学输出，短句、长段各一组。朗读内容则用 SenseVoice 核对性能测试中的 160 份音频，每种配置包含短句和长段各 10 次。

| 模型 | RMS 短句 | RMS 长段 | 一致率·官方·CPU | 一致率·官方·CUDA | 一致率·手机·GPU | 一致率·手机·GPU＋NPU |
|---|---:|---:|---:|---:|---:|---:|
| V2 Pro Plus | 0.2323% | 0.2351% | 98.87% | 99.06% | 99.34% | 99.72% |
| V5 Turbo | 1.4489% | 0.6537% | 99.53% | 99.25% | 99.72% | 99.62% |

- **RMS 相对误差** = `RMS(手机波形 − 官方波形) ÷ RMS(官方波形) × 100%`，越低表示计算结果越接近。
- **ASR 转写一致率** = `（1 − 字符编辑距离 ÷ 原文字数）× 100%`，去除标点后计算，越高越接近原文。

## 从源码构建

需要 JDK 21、Android SDK 37（含 NDK、CMake）和 Python 3.12+。

```powershell
git clone https://github.com/Renakoni/Voicebox.git
cd Voicebox
python -m pip install numpy onnx
python tools/prepare_language_assets.py
cd Android
.\gradlew.bat :app:assembleDebug
```

默认构建使用 CPU 推理。WebGPU 加速需构建定制 ONNX Runtime（[构建脚本](tools/build_ort_webgpu.ps1)、[补丁](patches)），通过 `-PvoiceboxOrtAar=<AAR路径>` 引入。

## 问题反馈

欢迎提交 [Issue](https://github.com/Renakoni/Voicebox/issues) 或 [Pull Request](https://github.com/Renakoni/Voicebox/pulls)。反馈问题请附应用版本、手机型号、使用的模型与复现步骤。

## 致谢与许可

Voicebox 基于 [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)、[Genie-TTS](https://github.com/High-Logic/Genie-TTS) 和 [RinStel Android](https://github.com/RinStel/Genie-TTS-Android) 开发。移动端优化参考了 [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp)，部分界面组件参考 [Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android)。

演示视频中的洛琪希（Roxy）音色来自 [Shinowing](https://github.com/Shinowing) 的 [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model)，感谢作者分享模型。

Voicebox 的原创代码与修改采用 [GNU AGPL-3.0](LICENSE) 许可。

第三方组件保留各自的版权与许可证，见 [licenses](licenses)、[原生依赖目录](Android/app/src/main/cpp/third_party) 和应用 assets 中的 `*-licenses` 目录。Release 附带相关依赖的对应源码。
