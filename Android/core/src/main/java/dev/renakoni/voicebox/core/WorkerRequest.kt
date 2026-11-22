package dev.renakoni.voicebox.core

import android.os.Bundle
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.i18n.LocalizedFailure
import dev.renakoni.voicebox.core.i18n.UiText

internal object WorkerProtocol {
    const val GENERATE = 1
    const val WARMUP = 2
    const val TRIM = 3
    const val CLOSE = 4
    const val EVENT = 5
    const val RESULT = 6
    const val PROBE = 7
    const val LIBRARY = 8
    const val CANCEL = 9
}

internal fun GenerationRequest.encode() = Bundle().apply {
    putString("request", context.requestId)
    putLong("epoch", context.generationEpoch)
    putString("streamDirectory", context.streamDirectory)
    putString("variant", characterModel.ref.variant)
    putString("model", characterModel.ref.modelId)
    putString("language", language)
    putString("promptLanguage", promptLanguage)
    putString("text", synthesisText)
    putString("reference", referenceAudioPath)
    putString("transcript", referenceText)
    putStringArrayList("auxiliary", ArrayList(auxiliaryReferenceAudioPaths))
    putBundle("options", Bundle().apply { options.forEach { (key, value) -> putString(key, value) } })
    require(listOf(synthesisText, referenceText).all { it.length <= 65_536 })
    require(auxiliaryReferenceAudioPaths.size <= 32 && auxiliaryReferenceAudioPaths.all { it.length <= 4096 })
    require(referenceAudioPath.length <= 4096 && options.size <= 32 && options.all { it.key.length <= 128 && it.value.length <= 1024 })
    require(listOf(context.requestId, language, promptLanguage, characterModel.ref.variant, characterModel.id).all { it.length <= 512 })
}

internal fun Bundle.decode(model: CharacterModel): GenerationRequest {
    val options = requireNotNull(getBundle("options")).let { values ->
        values.keySet().associateWith { values.getString(it)!! }
    }
    return GenerationRequest(model, getString("language")!!, getString("promptLanguage")!!, getString("text")!!,
        getString("reference")!!, getString("transcript")!!, getStringArrayList("auxiliary").orEmpty(),
        GenerationContext(getString("request")!!).apply {
            generationEpoch = getLong("epoch")
            streamDirectory = getString("streamDirectory")
        }, options)
}

internal fun UiText.encodeKey(): Bundle? {
    val name = key ?: return null
    val values = Bundle()
    arguments.forEachIndexed { index, value ->
        val field = index.toString()
        when (value) {
            is String -> values.putString(field, value.take(4096))
            is Int -> values.putInt(field, value)
            is Long -> values.putLong(field, value)
            is Float -> values.putFloat(field, value)
            is Double -> values.putDouble(field, value)
            is Boolean -> values.putBoolean(field, value)
            else -> return null
        }
    }
    return Bundle().apply { putString("key", name); putBundle("args", values) }
}

internal fun Bundle.workerFailure(message: String): BackendFailure {
    val code = getString("code") ?: "BACKEND_ERROR"
    val localized = getBundle("localized") ?: return BackendFailure(code, message)
    val args = requireNotNull(localized.getBundle("args"))
    val ui = UiText(requireNotNull(localized.getString("key")), *Array(args.size()) { requireNotNull(args.get(it.toString())) })
    return object : BackendFailure(code, message), LocalizedFailure { override val text = ui }
}
