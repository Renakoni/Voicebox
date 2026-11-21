package dev.renakoni.voicebox.model.ming

import android.content.Context
import dev.renakoni.voicebox.bridge.CharacterModel
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.bridge.ModelRef
import org.json.JSONObject
import java.io.File

object MingModel {
    const val ID = "ming-dense-0.5b"
    const val DIRECTORY = "ming/dense-0.5b/models"
    val model = CharacterModel(ModelRef("ming", "dense-0.5b", ID), "Ming-TTS 0.5B", DIRECTORY)
}

/** Model data belongs to the voice/model flow, independently of the downloaded backend. */
class MingModelResources(context: Context) {
    val directory = File(context.getExternalFilesDir(null), "CharacterModels/ming/dense-0.5b")
    val available get() = requiredFiles.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }
    val htp get() = File(directory, "htp").takeIf {
        htpFiles.all { path -> File(directory, path).let { file -> file.isFile && file.length() > 0 } }
    }

    fun reference(): ReferenceExample? {
        if (listOf("voice/manifest.json", "voice/speaker.f32", "voice/prompt.f32", "voice/history.f32", "examples/reference.wav")
                .any { !File(directory, it).isFile }) return null
        val transcript = runCatching { JSONObject(File(directory, "voice/manifest.json").readText()).getString("prompt_text") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return ReferenceExample(File(directory, "examples/reference.wav"), transcript, "zh")
    }

    companion object {
        private val htpFiles = listOf("htp/flow.onnx", "htp/flow.data", "htp/time.onnx", "htp/time.data",
            "models/aggregator-htp.onnx")
        private val requiredFiles = listOf(
            "models/tokenizer.json", "models/llm.onnx", "models/llm.data", "models/embedding.f32",
            "models/flow.onnx", "models/flow.data", "models/aggregator.onnx", "models/aggregator.data",
            "models/audio.onnx", "models/audio.data", "models/time.onnx", "models/time.data", "models/window.f32",
            "reference/encoder.onnx", "reference/encoder.data", "reference/speaker.onnx", "reference/campplus.onnx",
            "reference/projection.onnx", "reference/projection.data", "reference/mel.f32", "reference/povey.f32",
        )
    }
}
