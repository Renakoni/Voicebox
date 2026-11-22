<p align="center">
  <img src=".github/assets/voicebox.svg" width="112" height="112" alt="Voicebox のアプリアイコン">
</p>

<h1 align="center">Voicebox</h1>

<p align="center">
  <strong>Android 向けローカル・マルチモデル音声ワークステーション</strong><br>
  クラウドも外部 GPU も不要。複数のオープンソース音声合成・声質クローンモデルをスマホ単体でオフライン実行
</p>

<p align="center">
  <a href="README_ZH.md">中文</a> · <strong>日本語</strong> · <a href="README.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><img src="https://img.shields.io/github/v/release/Renakoni/Voicebox?style=flat-square&amp;label=release&amp;color=9A4930" alt="最新版"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-59665A?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 11 以降">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-655D57?style=flat-square" alt="ARM64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-6E665F?style=flat-square" alt="AGPL-3.0 ライセンス"></a>
  <br>
  <img src="https://img.shields.io/badge/Runtime-ONNX%20Runtime-4B6478?style=flat-square&amp;logo=onnx&amp;logoColor=white" alt="ONNX Runtime">
  <img src="https://img.shields.io/badge/GPU-WebGPU-4D6D73?style=flat-square&amp;logo=webgpu&amp;logoColor=white" alt="WebGPU">
  <img src="https://img.shields.io/badge/NPU-Qualcomm%20QNN-5C5675?style=flat-square&amp;logo=qualcomm&amp;logoColor=white" alt="Qualcomm QNN">
</p>

<p align="center">
  <a href="https://github.com/Renakoni/Voicebox/releases/latest"><strong>APK をダウンロード</strong></a>
  · <a href="#対応モデルとロードマップ">対応モデル</a>
  · <a href="#使い方">使い方</a>
  · <a href="#性能と品質">性能と品質</a>
  · <a href="#ソースからビルド">ビルド</a>
  · <a href="https://github.com/Renakoni/Voicebox/issues">不具合報告</a>
</p>

Voicebox は、主要なオープンソース TTS・声質クローンモデルを Android 端末上に集約し、ひとつのオフラインアプリで声のクローン、表現コントロール、ストリーミング再生、外部 API 連携まで完結させる音声ワークステーションです。約 5 秒の参照音声とその書き起こしがあれば、手元のスマホだけで声を再現できます。

> [!NOTE]
> 本プロジェクトは Qualcomm Snapdragon 向けに特化して最適化しています。本来のパフォーマンスを引き出すなら Snapdragon 端末がおすすめです。MediaTek Dimensity については……ご武運をお祈りします（単純に作者に Dimensity 端末を買うお金がなく、個別最適化できないだけです）。

https://github.com/user-attachments/assets/aca3f5e3-c458-4756-a44f-0a513cffe7f9

<sub>▶ デモ（33 秒、音声あり・中国語字幕）。動画内のロキシーのセリフは、すべて Xiaomi 14 上の Voicebox で生成しています。</sub>

## 対応モデルとロードマップ

Voicebox はプラグイン式の推論バックエンド設計を採用しています。各モデルのランタイムやリソースはアプリ内から必要に応じて個別に導入・削除でき、音声ライブラリ、出力機能、QQ ボイス送信、ローカル HTTP API はすべて共通で利用できます。

| モデル | ステータス | 概要 |
|---|---|---|
| [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) | ✅ 対応済み | V2 Pro / V2 Pro Plus / V5 Turbo 対応、クロスリンガル合成、`.ckpt`・`.pth` 重みの端末内変換 |
| [Ming-TTS 0.5B](https://github.com/inclusionAI/Ming-3.0-tts) | ✅ 対応済み | 93 種類の公式ボイス内蔵、1 音声でのクローン、感情・方言制御、読み上げ指示、ストリーミング再生 |
| [CosyVoice3-0.5B-2512](https://github.com/FunAudioLLM/CosyVoice) | 🔧 推論実装済み | モバイル推論の動作を確認済み。アプリへの組み込みを準備中 |
| [Qwen3-TTS-0.6B-Base](https://github.com/QwenLM/Qwen3-TTS) | 🔧 推論実装済み | モバイル推論の動作を確認済み。アプリへの組み込みを準備中 |
| [IndexTTS-2.5](https://github.com/index-tts/index-tts) | 🗺️ 対応予定 | 対応を計画中 |
| [Fish Audio S1-mini](https://github.com/fishaudio/fish-speech) | 🗺️ 対応予定 | 対応を計画中 |
| [OmniVoice](https://github.com/k2-fsa/OmniVoice) | 🗺️ 対応予定 | 対応を計画中 |

## 主な機能

- **マルチモデルのオンデマンド拡張**：アプリ本体と推論バックエンドを分離し、必要なモデルのランタイムとデータだけをアプリ内で導入・検証・削除できるため、APK の肥大化を防げます。
- **完全オフライン・ハードウェア加速**：モデルの準備後はネット接続もクラウド GPU も不要で、すべて端末内で完結します。GPU 加速対応のモバイル端末を前提に設計しており、対応する Snapdragon 端末では Qualcomm QNN による NPU ハイブリッド加速も利用できます。
- **ゼロショットクローンと多彩な表現制御**：GPT-SoVITS ではクロスリンガル合成や学習済み `.ckpt`・`.pth` ファイルの直接インポートに対応。Ming-TTS 0.5B では 93 種類の公式ボイスに加え、1 本の参照音声からのクローン、話速・音高・音量の調整、感情・方言の指定、自然言語による読み上げ指示、生成しながらのストリーミング再生が可能です。
- **QQ ネイティブボイスメッセージ送信**：生成した音声を添付ファイルではなく、タップしてそのまま再生できる QQ の正規ボイスメッセージとして送信できます。[QQ 送信デモを見る](https://github.com/Renakoni/Voicebox/blob/main/.github/assets/demo-qq.mp4)
- **ローカル HTTP API**：軽量な `127.0.0.1` HTTP サーバーを内蔵。朗読アプリや Tasker などの外部クライアントから呼び出せるほか、ADB ポート転送を使えば PC からも利用できます。
- **アプリ内アップデート検証**：GitHub の最新リリースをアプリ内から確認でき、ダウンロード後はファイルサイズ・SHA-256・パッケージ名とバージョンを自動で検証します。

## 使い方

[Releases](https://github.com/Renakoni/Voicebox/releases/latest) から **Voicebox 1.2.0** をダウンロードします（Android 11 以降の ARM64 端末が必要です）。

> [!WARNING]
> 1.2.0 は破壊的変更を含むリリースであり、旧バージョンへの上書きインストールはできません。以前のバージョンをお使いの場合は、一度アンインストールしてから 1.2.0 を導入してください（今後のバージョンはそのまま更新可能です）。

1. **モデルを導入**：アプリ内の **Models** 画面から、使いたい **GPT-SoVITS** または **Ming-TTS 0.5B** をインストールします。
2. **ボイスを準備**：**Voices** 画面で公式ボイスを選ぶか、参照音声の録音・読み込みと原稿テキストの入力で声をクローンします。GPT-SoVITS では手元の `.ckpt`・`.pth` 重みを直接インポートすることもできます。
3. **音声を生成・出力**：**Create** 画面でボイスと各種設定を選び、テキストを入力して生成します。完成した音声は再生、保存、共有、QQ への送信が可能です。

<details>
<summary><strong>QQ にボイスメッセージを送るための初回設定</strong></summary>

1. Android の開発者向けオプションで「ワイヤレス デバッグ」をオンにします。
2. Voicebox の **Send to QQ** 画面で **Connect** をタップし、Android に表示された 6 桁のペア設定コードを入力します（ペア設定は初回のみ）。
3. **Choose contact and send** をタップし、QQ で送信先のチャットを開きます。Voicebox が仮録音・音声差し替え・送信までを自動で行います。完了するまで QQ の操作は控えてください。

1 件あたりの音声は 60 秒未満にしてください。

</details>

## 性能と品質

スマートフォン側のベンチマークはすべて **Xiaomi 14（Snapdragon 8 Gen 3 / RAM 16 GB）** で実測しました。共通の中国語短文（約 3.5–4 秒）と長文（約 19–22 秒）を用い、公式の重みまたは固定のプリセットと乱数シードを使用。モデル読み込みとキャッシュ準備が完了した常温・サーマルスロットリングなしの状態で、各構成・長さごとに **10 回**ずつ生成し、平均と標本標準偏差を算出しています。

| モデル | 実行パス | 短文 RTF | 長文 RTF | ASR 一致率 |
|---|---|---:|---:|---:|
| GPT-SoVITS V2 Pro Plus | GPU | 0.614 | 0.556 | 99.34% |
| GPT-SoVITS V2 Pro Plus | GPU + NPU | 0.627 | 0.511 | 99.72% |
| GPT-SoVITS V5 Turbo | GPU | 2.038 | 1.816 | 99.72% |
| GPT-SoVITS V5 Turbo | GPU + NPU | 1.313 | 0.954 | 99.62% |
| Ming-TTS 0.5B | GPU | 1.821 | 1.055 | 100.00% / 100.00% |
| Ming-TTS 0.5B | GPU + NPU | 1.599 | 0.574 | 100.00% / 99.89% |

- **RTF（Real-Time Factor）**：推論時間 ÷ 音声の長さ。`1.0` 未満であれば実時間の再生スピードより速く生成できています。
- **ASR 一致率**：オフラインの SenseVoice で文字起こしし、句読点を除いた `(1 − 文字単位の編集距離 ÷ 入力文字数) × 100%` で算出。Ming-TTS 0.5B は短文 / 長文の順に記載しています。
- **実行パス**：GPU パスは CPU + WebGPU の協調動作、GPU + NPU パスは Qualcomm QNN による NPU ハイブリッド加速を併用します。

<details>
<summary><strong>GPT-SoVITS 詳細ベンチマーク</strong></summary>

#### 1. スマートフォンと PC（CUDA）の推論時間比較

比較対象には[公式リポジトリ](https://github.com/RVC-Boss/GPT-SoVITS)の CUDA 実装を使用（RTX 3070 Ti Laptop VRAM 8 GB / RAM 16 GB）。公式重みと同一の中国語参照音声で、短文約 4 秒・長文約 22 秒を生成しています。

| モデル | 実装 / 端末 | 短文生成時間（秒） | 短文 RTF | 長文生成時間（秒） | 長文 RTF |
|---|---|---:|---:|---:|---:|
| V2 Pro Plus | GPT-SoVITS · RTX 3070 Ti Laptop | 1.380 ± 0.382 | 0.341 ± 0.089 | 6.153 ± 0.301 | 0.265 ± 0.007 |
| V2 Pro Plus | Voicebox · GPU | 2.587 ± 0.182 | 0.614 ± 0.043 | 12.637 ± 0.453 | 0.556 ± 0.005 |
| V2 Pro Plus | Voicebox · GPU + NPU | 2.618 ± 0.927 | 0.627 ± 0.286 | 11.955 ± 0.608 | 0.511 ± 0.007 |
| V5 Turbo | GPT-SoVITS · RTX 3070 Ti Laptop | 1.680 ± 0.326 | 0.416 ± 0.086 | 6.217 ± 0.566 | 0.278 ± 0.021 |
| V5 Turbo | Voicebox · GPU | 8.173 ± 0.429 | 2.038 ± 0.102 | 40.676 ± 2.589 | 1.816 ± 0.043 |
| V5 Turbo | Voicebox · GPU + NPU | 5.367 ± 0.301 | 1.313 ± 0.074 | 21.479 ± 0.867 | 0.954 ± 0.021 |

#### 2. 波形の相対誤差と文字起こし一致率

入力とノイズを固定し、スマートフォン GPU + NPU の出力波形と公式 PyTorch FP32 の出力を比較しました。一致率は性能測定で生成した全音声を SenseVoice で文字起こしした結果です。

| モデル | 短文 RMS 誤差 | 長文 RMS 誤差 | 一致率 · 公式 CUDA | 一致率 · スマホ GPU | 一致率 · スマホ GPU + NPU |
|---|---:|---:|---:|---:|---:|
| V2 Pro Plus | 0.2323% | 0.2351% | 99.06% | 99.34% | 99.72% |
| V5 Turbo | 1.4489% | 0.6537% | 99.25% | 99.72% | 99.62% |

- **相対 RMS 誤差**：`RMS(スマホ波形 − 公式波形) ÷ RMS(公式波形) × 100%`。値が小さいほど公式 FP32 に近い出力です。
- モデルの計算特性やスケジューリングの詳細は[実装と最適化](docs/METHODS_JA.md)を参照してください。

</details>

<details>
<summary><strong>Ming-TTS 0.5B 詳細ベンチマーク</strong></summary>

反復測定には公式の中国語プリセット「`灵小柔`」を使用し、44.1 kHz・モノラル・16-bit PCM WAV で出力しました。

| 実行パス | テキスト | 音声長（秒） | リクエスト総時間（秒） | 推論 RTF | ASR 一致率 |
|---|---|---:|---:|---:|---:|
| GPU | 短文 | 3.57 ± 0.11 | 11.88 ± 0.50 | 1.821 ± 0.068 | 100.00% |
| GPU | 長文 | 19.19 ± 0.40 | 25.70 ± 1.03 | 1.055 ± 0.044 | 100.00% |
| GPU + NPU | 短文 | 3.59 ± 0.11 | 11.20 ± 0.42 | 1.599 ± 0.031 | 100.00% |
| GPU + NPU | 長文 | 19.17 ± 0.30 | 16.42 ± 0.52 | 0.574 ± 0.009 | 99.89% |

- **NPU 加速の効果**：QNN による NPU 加速を有効にすると、長文の平均 RTF が **45.5%** 低下し、リクエストから WAV 完成までの総時間が 25.70 秒から 16.42 秒へ短縮しました。
- **計測の内訳**：推論 RTF はコア推論時間を出力 WAV 長で割った値です。リクエスト総時間にはアプリ側の準備・セッション構築・WAV 書き出しも含まれます（長文では約 5.4 秒）。
- **コールドスタートとキャッシュ**：コールド状態の短文リクエストは GPU で 12.87 秒。NPU の初回コンパイル時は 17.53 秒かかり約 205 MB の QNN キャッシュを生成しますが、2 回目以降はキャッシュを再利用して 11.39 秒まで短縮します。
- **追加検証**：生成した WAV はすべて正常にデコードでき、クリッピングは 0 件でした。NPU パスでの中国語クローンおよび英語プリセットの検証でも、ASR 一致率はいずれも 100.00% でした。

</details>

## ソースからビルド

JDK 21、Android SDK 37（NDK・CMake を含む）、Python 3.12 以降が必要です。

```powershell
git clone https://github.com/Renakoni/Voicebox.git
cd Voicebox/Android
.\gradlew.bat :app:assembleDebug
```

標準ビルドは CPU 推論になります。WebGPU 加速を有効にするには、カスタム ONNX Runtime をビルドし（[ビルドスクリプト](tools/build_ort_webgpu.ps1)・[パッチ](Android/model/gptsovits/patches)）、`-PvoiceboxOrtAar=<AAR のパス>` を Gradle に渡してください。

## 不具合報告と貢献

[Issue](https://github.com/Renakoni/Voicebox/issues) や [Pull Request](https://github.com/Renakoni/Voicebox/pulls) を歓迎します。不具合を報告する際は、アプリバージョン、端末名、使用モデル、再現手順を添えていただけると助かります。

## クレジットとライセンス

以下のオープンソースプロジェクトと開発者の皆様に感謝します。

- モデル・推論基盤：[GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)、[Ming-3.0-tts](https://github.com/inclusionAI/Ming-3.0-tts)、[Genie-TTS](https://github.com/High-Logic/Genie-TTS)、[RinStel Android](https://github.com/RinStel/Genie-TTS-Android)、[gsv.cpp](https://github.com/KakaruHayate/gsv.cpp)
- UI・コンポーネント参考：[Microsoft Fluent UI Android](https://github.com/microsoft/fluentui-android)、[NextVol](licenses/NextVol-Apache-2.0.txt)
- デモ動画のロキシーの声には、[Shinowing](https://github.com/Shinowing) 氏による [Shinowing_AI_model](https://github.com/Shinowing/Shinowing_AI_model) を使用しました。モデルの公開に感謝します。

Voicebox 独自のコードおよび改変部分は [GNU AGPL-3.0](LICENSE) で公開しています。

サードパーティ製コンポーネントにはそれぞれの著作権とライセンスが適用されます。詳細は [licenses](licenses)、[アプリ](Android/app/src/main/cpp/third_party)・[共通音声処理](Android/core/src/main/cpp/third_party)・[GPT-SoVITS](Android/model/gptsovits/src/main/cpp/third_party) のネイティブ依存ディレクトリ、およびアプリ・ブリッジ内の `*-licenses` を参照してください。該当依存ライブラリの対応ソースはリリースに添付しています。
