package dev.renakoni.voicebox.studio

import dev.renakoni.voicebox.bridge.ModelRef
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StudioVoice(
    val id: String,
    val name: String,
    val model: ModelRef,
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

    fun outputLanguage(backendId: String) = preferences.getString("language/$backendId", null)
    fun saveLanguage(backendId: String, language: String) {
        preferences.edit().putString("language/$backendId", language).apply()
    }

    fun options(backendId: String): Map<String, String> {
        val data = JSONObject(preferences.getString("options/$backendId", "{}")!!)
        return data.keys().asSequence().associateWith { data.getString(it) }
    }

    fun saveOptions(backendId: String, values: Map<String, String>) {
        preferences.edit().putString("options/$backendId", JSONObject(values).toString()).apply()
    }

    fun voices(): List<StudioVoice> {
        val array = JSONArray(preferences.getString("voices", "[]"))
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            StudioVoice(item.getString("id"), item.getString("name"),
                ModelRef(item.getString("backend"), item.getString("variant"), item.getString("model")),
                item.getString("audio"), item.getString("audioName"), item.getString("transcript"),
                item.optLong("duration"), referenceLanguage = item.optString("referenceLanguage", "auto"))
        }
    }

    fun save(voices: List<StudioVoice>) {
        val array = JSONArray()
        voices.forEach { voice ->
            array.put(JSONObject().put("id", voice.id).put("name", voice.name)
                .put("backend", voice.model.backendId).put("variant", voice.model.variant)
                .put("model", voice.model.modelId).put("audio", voice.audioPath)
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
