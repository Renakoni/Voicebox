<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox 应用图标">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>把 GPT-SoVITS 装进口袋。</strong><br>
  无需云端与显卡，在 Android 本地实现高质量语音克隆，中文 · 日本語 · English
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
  · <a href="README_EN.md">English</a>
</p>
5 秒样本，即可在本地复刻任意音色。全程离线运行于 Android 端侧，不依赖服务器与桌面显卡，实现快于实时的语音合成。

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>▶ 演示（33 秒，有声音）。视频里的 Roxy 语音均由小米 14 上的 Voicebox 实际生成。</sub>



## 核心特性

- **真·端侧离线**：模型下载完成后，生成全程在手机上进行。GPU 走 WebGPU，高通设备还能叠加 NPU 混合加速；没有可用 GPU 时自动回到 CPU。
- **原生权重直导**：直接选中 GPT-SoVITS 训练出的 `.ckpt` 和 `.pth`，在手机上转换成可用模型，支持 V2 Pro、V2 Pro Plus 和 V5 Turbo，不用先在电脑上导出。
- **一键发成 QQ 语音条**：生成完点「去 QQ 发送」，选好联系人，发出去的是对方点开就能听的原生语音消息。 [查看 QQ 发送演示（14 秒）](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **本机 HTTP 服务**：在设置中开启本地 API，方便 Tasker、听书软件等第三方客户端调用手机生成语音；电脑端可通过 ADB 端口转发接入。

另外，生成语言和参考录音语言分开设置：用日语录音克隆的音色，照样能说中文和英文。

## 快速上手

从 [Releases](https://github.com/Renakoni/Voicebox/releases/latest) 下载 **Voicebox 1.1.1**（72.6 MiB）。

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

> **省流**：在小米 14（骁龙 8 Gen 3）上，V2 Pro Plus 生成一句 4 秒的话约需 **2.6 秒**；长段落 RTF 约 **0.51**，生成速度大约是播放速度的两倍。V5 Turbo 开启 NPU 后长段 RTF 降到 **0.95**，同样快于播放。

下表中，短句约 **4 秒**、长段约 **22 秒**音频，使用官方权重和同一份中文参考录音。模型加载与缓存准备完成后，每种配置、每种长度生成 **10 次**，结果为**均值 ± 标准差**。

参与比较的 GPT-SoVITS 使用[官方仓库](https://github.com/RVC-Boss/GPT-SoVITS)的模型与实现，测试电脑为 **i9-12900H、RTX 3070 Ti Laptop（8 GB 显存）、16 GB RAM**。Voicebox 均在**小米 14（骁龙 8 Gen 3、16 GB RAM）**上测试。

| 模型 | 实现 / 设备 | 短句耗时（秒） | 短句 RTF ↓ | 长段耗时（秒） | 长段 RTF ↓ |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · i9-12900H | 7.008 ± 0.950 | 1.752 ± 0.249 | 39.173 ± 1.097 | 1.731 ± 0.030 |
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU＋NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · i9-12900H | 12.660 ± 0.859 | 3.162 ± 0.209 | 71.061 ± 3.277 | 3.137 ± 0.049 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU＋NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

**RTF = 生成耗时 ÷ 音频时长**，越小越快；小于 1 表示生成快于播放。手机 GPU 为 CPU＋WebGPU，NPU 为 Qualcomm QNN 混合加速。NPU 是可选的实验路径：安装包不附带 QNN 运行库，只有预先配置好兼容运行库并通过验证的设备才会启用，否则使用 GPU。

## 生成质量

> **省流**：同样的输入和噪声下，V2 Pro Plus 手机输出与官方 PyTorch FP32 的波形误差约 **0.23%**；用语音识别回听 160 份音频，手机生成的转写一致率在 **99%** 以上，与电脑上的官方实现处在同一水平。

波形误差比较 GPU＋NPU 与官方 PyTorch FP32 的完整声学输出，固定输入和噪声，短句、长段各 1 组。转写一致率使用 SenseVoice 核对上述 160 份音频，每种配置包含短句、长段各 10 次。

| 模型 | RMS 短句 ↓ | RMS 长段 ↓ | 一致率·官方·CPU ↑ | 一致率·官方·CUDA ↑ | 一致率·手机·GPU ↑ | 一致率·手机·GPU＋NPU ↑ |
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

默认构建使用 CPU 推理。WebGPU 加速需构建定制 ONNX Runtime（[构建脚本](tools/build_ort_webgpu.ps1)、[补丁](patches)），通过 `-PgenieOrtAar=<AAR路径>` 引入。

## 问题反馈

欢迎提交 [Issue](https://github.com/Renakoni/Voicebox/issues) 或 [Pull Request](https://github.com/Renakoni/Voicebox/pulls)。反馈问题请附应用版本、手机型号、使用的模型与复现步骤。

## 致谢与许可

Voicebox 基于 [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)、[Genie-TTS](https://github.com/High-Logic/Genie-TTS) 和 [RinStel Android](https://github.com/RinStel/Genie-TTS-Android) 开发。移动端优化参考了 [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp)，部分界面组件参考 [Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android)。

同时感谢 [ONNX Runtime](https://github.com/microsoft/onnxruntime) 提供推理运行时、[libadb-android](https://github.com/MuntashirAkon/libadb-android) 提供无线调试连接能力，以及 [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) 支持测试音频的转写核对。

演示视频中的洛琪希（Roxy）音色来自 [Shinowing](https://github.com/Shinowing) 的 [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model)，感谢作者分享模型。

Voicebox 的原创代码与修改采用 [GNU AGPL-3.0](LICENSE) 许可。

第三方组件保留各自的版权与许可证，见 [licenses](licenses)、[原生依赖目录](Android/app/src/main/cpp/third_party) 和应用 assets 中的 `*-licenses` 目录。Release 附带相关依赖的对应源码。
