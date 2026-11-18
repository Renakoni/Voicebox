<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox のアプリアイコン">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>GPT-SoVITS をポケットに。</strong><br>
  クラウドも外部GPUも不要、Android 端末だけで高品質な音声クローンを実現。<br>
  <a href="README.md">中文</a> · <a href="README_JA.md">日本語</a> · <a href="README_EN.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><img src="https://img.shields.io/github/v/release/Renakoni/Voicebox?style=flat-square&amp;label=release&amp;color=9A4930" alt="最新版"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-59665A?style=flat-square" alt="Android 11 以降">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-655D57?style=flat-square" alt="ARM64">
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><strong>APK をダウンロード</strong></a>
  · <a href="#使い方">使い方</a>
  · <a href="#推論性能">性能</a>
  · <a href="#ソースからビルド">ビルド</a>
  · <a href="https://github.com/Renakoni/Voicebox/issues">不具合報告</a>
</p>

約 5 秒の参照音声とその原稿テキストがあれば、スマートフォン上で音声を再現できます。

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>デモ（33 秒、音声あり、中国語字幕）。動画内のロキシーのセリフは、すべて Xiaomi 14 上の Voicebox で生成しました。</sub>

## 主な機能

- **完全端末内・オフライン推論**：モデルダウンロード後は、クラウドや外部 GPU を使わず端末ローカルのみで処理。WebGPU による GPU 加速に対応し、対応する Snapdragon 搭載端末では NPU とのハイブリッド加速も利用可能です。
- **言語をまたいだクロスリンガル合成**：参照音声の言語と生成テキストの言語は完全に独立しており、言語の壁を越えた音声クローンが可能です（例：日本語の参照音声で中国語や英語を読み上げ）。
- **学習済みモデルの直接インポート**：GPT-SoVITS で学習した `.ckpt` および `.pth` 重みファイルを直接読み込み、端末内で変換。V2 Pro、V2 Pro Plus、V5 Turbo の各モデルに対応しています。
- **QQ ネイティブボイスメッセージ送信**：生成した音声を、相手がタップしてそのまま聴ける QQ のネイティブボイスメッセージとして直接送信可能。[QQ 送信デモ（14 秒）](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **ローカル HTTP サーバー機能**：内蔵の軽量 HTTP API を有効化することで、Tasker や読書・朗読アプリなどのサードパーティから直接音声合成を呼び出し可能。PC からも ADB のポート転送経由でアクセスできます。

## 使い方

[Releases](https://github.com/Renakoni/Voicebox/releases/latest) から **Voicebox 1.1.2**（72.6 MiB）をダウンロードします。**Android 11 以降の ARM64 端末**に対応しています。以前の公式版には上書きインストールできます。

1. 「音色」タブで公式の V2 Pro Plus または V5 Turbo をダウンロードします。共通データも一緒に取得します。手持ちの重みを使う場合は「导入音色」をタップし、`.ckpt` と `.pth` を選びます。
2. ベースモデルを選びます。長押しで録音するか、参照音声を選んでください。録音で話している内容と、その言語を設定して保存します。
3. 「创作」タブで声と出力言語を選び、テキストを入力して「生成语音」をタップします。生成した音声は再生、保存、共有、QQ への送信ができます。

<details>
<summary><strong>QQ にボイスメッセージを送るための初回設定</strong></summary>

1. Android の開発者向けオプションで「ワイヤレス デバッグ」をオンにします。
2. Voicebox の「发送 QQ 语音」画面で「连接」をタップし、Android に表示された 6 桁のコードを入力します。ペア設定は初回のみです。
3. 「选择联系人并发送」をタップし、QQ で送信先のチャットを開きます。Voicebox は仮の音声を録音し、生成した音声に差し替えて送信します。送信が終わるまで QQ の操作は控えてください。

1 件あたりの音声は 60 秒未満にしてください。

</details>

## 推論性能

> **要約**：Snapdragon 8 Gen 3 において、V2 Pro Plus は 4 秒の音声を約 **2.6 秒**で生成。長文での RTF は約 **0.51**（再生速度の約 2 倍のスピード）を記録しました。V5 Turbo も NPU 加速を有効化することで長文 RTF が **0.95** となり、実時間より高速に生成できます。

各構成で公式の重みと同じ中国語の参照音声を使いました。生成された音声の長さは、短文で約 **4 秒**、長文で約 **22 秒**です。モデルとキャッシュの準備後に、構成ごとに短文と長文をそれぞれ **10 回**生成し、**平均 ± 標本標準偏差**を示しています。

GPT-SoVITS は [公式リポジトリ](https://github.com/RVC-Boss/GPT-SoVITS) のモデルと実装を使用しました。PC は **i9-12900H、RTX 3070 Ti Laptop（VRAM 8 GB）、RAM 16 GB**、Voicebox の測定端末は **Xiaomi 14（Snapdragon 8 Gen 3、RAM 16 GB）** です。

| モデル | 実装 / 端末 | 短文の生成時間（秒） | 短文 RTF | 長文の生成時間（秒） | 長文 RTF |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · i9-12900H | 7.008 ± 0.950 | 1.752 ± 0.249 | 39.173 ± 1.097 | 1.731 ± 0.030 |
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU + NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · i9-12900H | 12.660 ± 0.859 | 3.162 ± 0.209 | 71.061 ± 3.277 | 3.137 ± 0.049 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU + NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

- **RTF（リアルタイムファクター）** = 生成時間 ÷ 音声の長さ。1 未満であれば実際の再生時間よりも高速に生成できていることを示します。
- **ハードウェアバックエンド**：スマートフォンの GPU 構成は CPU＋WebGPU の協調動作、GPU＋NPU 構成は Qualcomm QNN によるハードウェアアクセラレーションを利用します（要互換ランタイム）。未対応端末では自動的に CPU 推論へフォールバックします。
- 両モデルの計算特性とハードウェア分散スケジューリングの詳細は、[実装と最適化](docs/METHODS_JA.md) をご覧ください。

## 生成品質

同じ入力とノイズを使った場合、スマートフォン上の V2 Pro Plus と公式 PyTorch FP32 の波形の相対 RMS 誤差は、約 **0.23%** でした。今回の短文と長文では、スマートフォンの各構成で、文字起こしと入力文の一致率が **99%** を超えました。

波形の比較では入力とノイズを固定し、GPU + NPU と公式 PyTorch FP32 の音響処理の出力を、短文と長文で各 1 組ずつ比べました。読み上げ内容の確認には SenseVoice を使い、性能測定で生成した 160 件を文字起こししました。各構成につき短文と長文が 10 件ずつです。

| モデル | 短文の RMS 誤差 | 長文の RMS 誤差 | 一致率・公式 CPU | 一致率・公式 CUDA | 一致率・スマートフォン GPU | 一致率・スマートフォン GPU + NPU |
|---|---:|---:|---:|---:|---:|---:|
| V2 Pro Plus | 0.2323% | 0.2351% | 98.87% | 99.06% | 99.34% | 99.72% |
| V5 Turbo | 1.4489% | 0.6537% | 99.53% | 99.25% | 99.72% | 99.62% |

- **相対 RMS 誤差**は `RMS(スマートフォンの波形 − 公式実装の波形) ÷ RMS(公式実装の波形) × 100%` です。値が小さいほど波形が近いことを示します。
- **文字起こしの一致率**は、句読点を除いて `(1 − 文字単位の編集距離 ÷ 入力文の文字数) × 100%` で計算します。

## ソースからビルド

JDK 21、Android SDK 37（NDK と CMake を含む）、Python 3.12 以降が必要です。

```powershell
git clone https://github.com/Renakoni/Voicebox.git
cd Voicebox
python -m pip install numpy onnx
python tools/prepare_language_assets.py
cd Android
.\gradlew.bat :app:assembleDebug
```

標準のビルドでは CPU で推論します。WebGPU を使うには、専用の ONNX Runtime をビルドし、`-PgenieOrtAar=<AAR のパス>` を Gradle に渡してください。[ビルドスクリプト](tools/build_ort_webgpu.ps1) · [パッチ](patches)

## 不具合報告と貢献

[Issue](https://github.com/Renakoni/Voicebox/issues) や [Pull Request](https://github.com/Renakoni/Voicebox/pulls) を歓迎します。不具合の報告には、アプリのバージョン、端末名、使用した音声モデル、再現手順を添えてください。

## クレジットとライセンス

Voicebox は [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)、[Genie-TTS](https://github.com/High-Logic/Genie-TTS)、[RinStel Android](https://github.com/RinStel/Genie-TTS-Android) を基に開発しました。モバイル向けの最適化には [gsv.cpp](https://github.com/KakaruHayate/gsv.cpp) を、一部の UI コンポーネントには [Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android) を参考にしました。

デモのロキシーの声には、[Shinowing](https://github.com/Shinowing) が公開した [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model) を使用しました。モデルの公開に感謝します。

Voicebox 独自のコードと変更部分は [GNU AGPL-3.0](LICENSE) で公開しています。

第三者のコンポーネントには、それぞれの著作権とライセンスが適用されます。[licenses](licenses)、[ネイティブ依存ライブラリ](Android/app/src/main/cpp/third_party)、アプリの assets 内の `*-licenses` を参照してください。該当する依存ライブラリの対応ソースはリリースに添付しています。
