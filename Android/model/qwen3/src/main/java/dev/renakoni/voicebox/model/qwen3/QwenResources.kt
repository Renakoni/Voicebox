package dev.renakoni.voicebox.model.qwen3

import android.content.Context
import java.io.File

internal class QwenResources(context: Context) {
    private val directory = File(context.noBackupFilesDir, "qwen3-model")
    val talker = File(directory, "qwen-talker-0.6b-base-Q4_K_M.gguf")
    val codec = File(directory, "qwen-tokenizer-12hz-F32.gguf")
}
