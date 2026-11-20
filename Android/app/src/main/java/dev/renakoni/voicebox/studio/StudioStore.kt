package dev.renakoni.voicebox.studio

import android.content.Context
import dev.renakoni.voicebox.inference.SynthesisOptions
import org.json.JSONArray
import org.json.JSONObject

data class StudioVoice(
    val id: String,
    val name: String,
    val modelDirectory: String,
    val audioPath: String,
    val audioName: String,
    val transcript: String,
    val durationMs: Long = 0,
    val builtIn: Boolean = false,
    val referenceLanguage: String = "auto",
    val avatarPath: String = "",
)

data class StudioOutput(
    val path: String,
    val voiceName: String,
    val text: String,
    val durationMs: Long,
)

/** User-authored voices live outside the engine's disposable feature cache. */
class StudioStore(context: Context) {
    private val preferences = context.getSharedPreferences("voice_studio", Context.MODE_PRIVATE)

    var selectedId: String
        get() = preferences.getString("selected_voice", "").orEmpty()
        set(value) { preferences.edit().putString("selected_voice", value).apply() }
    var text: String
        get() = preferences.getString("text", "").orEmpty()
        set(value) { preferences.edit().putString("text", value).apply() }
    var gpuEnabled: Boolean
        get() = preferences.getBoolean("gpu", true)
        set(value) { preferences.edit().putBoolean("gpu", value).apply() }

    var outputLanguage: String
        get() = preferences.getString("output_language", "zh") ?: "zh"
        set(value) { preferences.edit().putString("output_language", value).apply() }

    var synthesisOptions: SynthesisOptions
        get() = with(SynthesisOptions.Default) {
            runCatching { copy(
                speed = preferences.getString("speed", null)?.toDouble() ?: speed,
                temperature = preferences.getFloat("temperature", temperature),
                topK = preferences.getInt("topK", topK),
                topP = preferences.getFloat("topP", topP),
                repetitionPenalty = preferences.getFloat("repetitionPenalty", repetitionPenalty),
            ) }.getOrDefault(this)
        }
        set(value) { preferences.edit().putString("speed", value.speed.toString())
            .putFloat("temperature", value.temperature).putInt("topK", value.topK)
            .putFloat("topP", value.topP).putFloat("repetitionPenalty", value.repetitionPenalty).apply() }

    fun voices(): List<StudioVoice> {
        val array = JSONArray(preferences.getString("voices", "[]"))
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            StudioVoice(item.getString("id"), item.getString("name"), item.getString("model"),
                item.getString("audio"), item.getString("audioName"), item.getString("transcript"),
                item.optLong("duration"), referenceLanguage = item.optString("referenceLanguage", "auto"))
        }
    }

    fun save(voices: List<StudioVoice>) {
        val array = JSONArray()
        voices.forEach { voice ->
            array.put(JSONObject().put("id", voice.id).put("name", voice.name)
                .put("model", voice.modelDirectory).put("audio", voice.audioPath)
                .put("audioName", voice.audioName).put("transcript", voice.transcript)
                .put("duration", voice.durationMs).put("referenceLanguage", voice.referenceLanguage))
        }
        preferences.edit().putString("voices", array.toString()).apply()
    }

    var output: StudioOutput?
        get() = preferences.getString("output", null)?.let { raw ->
            val item = JSONObject(raw)
            StudioOutput(item.getString("path"), item.getString("voice"), item.getString("text"),
                item.getLong("duration"))
        }
        set(value) {
            val json = value?.let { JSONObject().put("path", it.path).put("voice", it.voiceName)
                .put("text", it.text).put("duration", it.durationMs).toString() }
            preferences.edit().putString("output", json).apply()
        }
}
