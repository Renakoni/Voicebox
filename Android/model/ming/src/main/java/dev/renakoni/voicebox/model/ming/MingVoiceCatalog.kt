package dev.renakoni.voicebox.model.ming

import dev.renakoni.voicebox.bridge.SynthesisVoicePreset
import java.io.File
import org.json.JSONObject

internal object MingVoiceCatalog {
    const val FileName = "voice-presets.json"
    private const val OfficialSource = "inclusionAI/Ming-3.0-tts-0.5B"

    fun read(file: File): List<SynthesisVoicePreset> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val catalog = JSONObject(file.readText(Charsets.UTF_8))
            if (catalog.getInt("format") != 1 || catalog.getString("source_model") != OfficialSource) return emptyList()
            val voices = catalog.getJSONArray("voices")
            buildList {
                for (index in 0 until voices.length()) {
                    val voice = voices.getJSONObject(index)
                    val id = voice.getString("ip")
                    val language = voice.getString("language")
                    add(SynthesisVoicePreset(id, language, recommended = voice.optBoolean("recommended")))
                    val variants = voice.optJSONArray("variants") ?: continue
                    for (variant in 0 until variants.length()) {
                        add(SynthesisVoicePreset(variants.getString(variant), language, baseId = id))
                    }
                }
            }.also { entries ->
                val ids = entries.map { it.id }
                require(ids.none(String::isBlank) && ids.toSet().size == ids.size)
            }
        }.getOrDefault(emptyList())
    }
}
