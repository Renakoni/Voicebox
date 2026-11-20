package dev.renakoni.voicebox.core

import android.os.Bundle
import dev.renakoni.voicebox.bridge.*

internal object WorkerProtocol {
    const val GENERATE = 1
    const val WARMUP = 2
    const val TRIM = 3
    const val CLOSE = 4
    const val EVENT = 5
    const val RESULT = 6
}

internal fun GenerationRequest.encode() = Bundle().apply {
    putString("request", context.requestId)
    putLong("epoch", context.generationEpoch)
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

internal fun Bundle.decode(provider: BackendProvider): GenerationRequest {
    val ref = ModelRef(provider.id, getString("variant")!!, getString("model")!!)
    val model = provider.discoverCharacterModels().single { it.ref == ref }
    val options = requireNotNull(getBundle("options")).let { values ->
        provider.validateOptions(values.keySet().associateWith { values.getString(it)!! })
    }
    return GenerationRequest(model, getString("language")!!, getString("promptLanguage")!!, getString("text")!!,
        getString("reference")!!, getString("transcript")!!, getStringArrayList("auxiliary").orEmpty(),
        GenerationContext(getString("request")!!).apply { generationEpoch = getLong("epoch") }, options)
}
