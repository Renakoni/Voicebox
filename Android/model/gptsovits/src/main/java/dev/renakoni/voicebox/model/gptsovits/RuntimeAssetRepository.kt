package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.ModelInspectionResult

import dev.renakoni.voicebox.core.OutputStorage
import dev.renakoni.voicebox.core.regularFileAttributes
import android.content.Context
import java.io.File

class RuntimeAssetRepository(private val filesRoot: File) {
    constructor(context: Context) : this(requireNotNull(context.getExternalFilesDir(null)))

    fun runtimeRoot(): File = File(filesRoot, RUNTIME_ASSET_DIRECTORY)

    fun outputRoot(): File = OutputStorage(filesRoot).outputRoot()

    internal fun referenceAudioCacheDirectory(): File = File(filesRoot, "ReferenceAudioCache")

    internal fun referenceAudioFeatureModelFiles(): List<File> =
        listOf(hubertModelFile(), hubertFp16WeightsFile(), hubertFp32WeightsFile(), speakerEncoderFile())

    fun inspect(): ModelInspectionResult {
        val root = runtimeRoot()
        return ModelInspectionResult(
            missingFiles = requiredFiles.filter { (File(root, it).regularFileAttributes()?.size() ?: 0L) == 0L },
        )
    }

    @Synchronized
    fun prepareRuntimeWeights(progress: (Long, Long) -> Unit = { _, _ -> }) {
        HalfPrecisionFileConverter.prepare(filesRoot, hubertFp16WeightsFile(), hubertFp32WeightsFile(), progress)
    }

    fun clearDerivedFiles() = (listOf(hubertFp32WeightsFile()) +
            referenceAudioCacheDirectory().listFiles().orEmpty().toList())
            .count { file -> file.isFile && file.delete() }

    fun hubertModelFile(): File =
        File(File(runtimeRoot(), "chinese-hubert-base"), "chinese-hubert-base.onnx")

    fun speakerEncoderFile(): File =
        File(runtimeRoot(), "speaker_encoder.onnx")

    private fun hubertFp16WeightsFile(): File =
        File(runtimeRoot(), HUBERT_FP16)

    private fun hubertFp32WeightsFile(): File =
        File(runtimeRoot(), HUBERT_FP32)

    companion object {
        const val RUNTIME_ASSET_DIRECTORY = "RuntimeAssets"

        internal const val HUBERT_FP16 = "chinese-hubert-base/chinese-hubert-base_weights_fp16.bin"
        internal const val HUBERT_FP32 = "chinese-hubert-base/chinese-hubert-base_weights.bin"
        internal const val HUBERT_MANIFEST = "chinese-hubert-base/chinese-hubert-base_weights_fp16_manifest.json"
        internal val requiredFiles = listOf(
            "chinese-hubert-base/chinese-hubert-base.onnx",
            HUBERT_FP16,
            HUBERT_MANIFEST,
            "speaker_encoder.onnx",
            "roberta-wwm-ext-large-onnx/model.onnx",
            "roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json",
        )
    }
}
