package dev.renakoni.voicebox

import android.net.Uri
import android.os.Bundle
import dev.renakoni.voicebox.bridge.LibraryAction
import dev.renakoni.voicebox.bridge.ModelRef
import dev.renakoni.voicebox.bridge.ReferenceExample
import dev.renakoni.voicebox.bridge.VoiceImport

sealed class VoiceCheckpointSource(val backendId: String, val format: VoiceImport, val action: LibraryAction) {
    internal abstract fun arguments(name: String): Bundle

    class GptSoVits(val gpt: Uri, val sovits: Uri, val reference: ReferenceExample? = null,
        val replace: ModelRef? = null, val gptFilename: String = "", val sovitsFilename: String = "") :
        VoiceCheckpointSource("gptsovits", VoiceImport.CHECKPOINT_PAIR, LibraryAction.IMPORT_PAIR) {
        override fun arguments(name: String) = referenceArguments(name, reference).apply {
            require(replace == null || replace.backendId == backendId)
            putString("first", gpt.toString())
            putString("second", sovits.toString())
            putString("id", replace?.modelId)
            putString("variant", replace?.variant)
            putString("firstName", gptFilename)
            putString("secondName", sovitsFilename)
        }
    }

    class Qwen3(val config: Uri, val weights: Uri) :
        VoiceCheckpointSource("qwen3", VoiceImport.QWEN_SAFETENSORS, LibraryAction.IMPORT_QWEN_CHECKPOINT) {
        override fun arguments(name: String) = Bundle().apply {
            putString("name", name)
            putString("config", config.toString())
            putString("weights", weights.toString())
        }
    }
}

internal fun referenceArguments(name: String, reference: ReferenceExample?) = Bundle().apply {
    putString("name", name)
    reference?.let {
        putString("reference", it.audioFile.absolutePath)
        putString("transcript", it.referenceText)
        putString("language", it.language)
    }
}
