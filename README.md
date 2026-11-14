# Voicebox

Android 本地语音克隆与文字转语音应用，支持中文、日语和英语。

- Android 11 及以上，正式 APK 仅 ARM64。
- 在手机上导入 GPT-SoVITS V2ProPlus 的 `.ckpt` + `.pth`，配置参考音频和原文后使用。
- 支持录音克隆、音色编辑、音频播放与分享，以及独立的 QQ 语音发送入口。
- 采用 CPU + WebGPU 推理；GPU 不可用时使用 CPU。
- APK 不附带角色或公共 TTS 权重，资源需单独导入。

## 构建

工具链：JDK 21、Android SDK 37、NDK 28.2.13676358、CMake 3.22.1、Python 3.12+。Gradle 使用仓库内的 wrapper。

先在仓库根目录准备离线语言资源；下载和生成文件放在仓库同级的 `assets` 目录，不进入 Git：

```powershell
python -m pip install numpy onnx
python tools/prepare_language_assets.py
cd Android
.\gradlew.bat :app:assembleDebug
```

默认使用官方 ONNX Runtime 1.27.0。包含 CPU 内核优化和 WebGPU 加速的构建需通过 `-PgenieOrtAar=<自定义 AAR 路径>` 指定运行库。

正式签名读取 `Android/signing/voicebox-release.jks` 和 `voicebox-release.password`，密钥别名为 `voicebox_release`。创建并保管自己的签名材料，这些文件不提交到 Git。

```powershell
# 在 Android 目录执行，替换 AAR 路径。
.\gradlew.bat '-PgenieOrtAar=C:/path/to/onnxruntime-voicebox.aar' :app:assembleRelease
```

输出为 `Android/app/build/outputs/apk/release/app-release.apk`，版本 1.0.0。正式包启用 R8 与资源裁剪，固定为 ARM64；APK 不包含模型下载包。调试构建可用 `-PgenieAbi=x86_64` 指定模拟器架构。

### 自定义运行库

基于 [ONNX Runtime v1.27.0](https://github.com/microsoft/onnxruntime/tree/v1.27.0)，提交 `8f0278c77bf44b0cc83c098c6c722b92a36ac4b5`。依次应用 `patches` 中的 Conv1D、activation-cache、cpu-half-storage 三份补丁。

[build_ort_webgpu.ps1](tools/build_ort_webgpu.ps1) 构建 ARM64 原生库；脚本参数指定源码、Python 构建环境、Android SDK/NDK 和 protoc 路径。Python 依赖见 [ort-build-requirements.txt](tools/ort-build-requirements.txt)，另需 Node.js 22 和 protoc 21.12。编译后的 `libonnxruntime.so` 使用 NDK 的 `llvm-strip --strip-unneeded` 去除调试符号，再通过以下脚本替换同版本官方 AAR 中的运行库：

```powershell
python tools/package_ort_runtime.py --aar <官方1.27.0的AAR> --library <libonnxruntime.so> --output <新的AAR路径>
```

公共资源包可用 `:app:bundleRuntimeAssets -PgenieRuntimeAssets=<GenieData目录>` 单独生成，不会打进 APK。权重来源见 [Genie 公共资源](https://huggingface.co/High-Logic/Genie/tree/52b17272e0b7032415e85ad37b551db2386b1810/GenieData)。

## 检查

```powershell
# 在 Android 目录执行；不会启动设备或模拟器。
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

## 来源与许可

Voicebox 基于 [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)、[Genie-TTS](https://github.com/High-Logic/Genie-TTS) 和 [RinStel Android](https://github.com/RinStel/Genie-TTS-Android) 开发。部分优化参考 [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp)，界面组件参考 Microsoft Fluent UI Android。

上游版权及许可证保留于 [LICENSE](LICENSE)、[licenses](licenses)、[原生依赖目录](Android/app/src/main/cpp/third_party) 和应用 assets 中的 `*-licenses` 目录。各第三方组件遵循其各自许可证。
