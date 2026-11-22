package dev.renakoni.voicebox.model.ming

import org.json.JSONObject

internal object MingSynthesisOptions {
    private val speeds = mapOf("slow" to "慢速", "normal" to "中速", "fast" to "快速")
    private val pitches = mapOf("low" to "低", "medium" to "中", "high" to "高")
    private val volumes = pitches
    private val emotions = mapOf("neutral" to "中性", "happy" to "高兴", "sad" to "悲伤", "angry" to "愤怒",
        "fearful" to "恐惧", "disgusted" to "厌恶", "surprised" to "惊讶")
    private val dialects = setOf("cantonese", "chuanyu dialect")
    private val basic = setOf("speed", "pitch", "volume")
    private val groups = listOf(basic, setOf("emotion"), setOf("dialect"), setOf("instruction", "use_reference"), setOf("ip"))
    private val controls = groups.flatten().toSet()
    private val keys = controls + "seed"
    private val choices = mapOf("speed" to speeds.keys, "pitch" to pitches.keys, "volume" to volumes.keys,
        "emotion" to emotions.keys, "dialect" to dialects, "use_reference" to setOf("true", "false"))

    fun merge(defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String> {
        val group = groups.firstOrNull { fields -> fields.any { it in overrides } }
        val replaced = if (group == null) emptySet() else controls - group
        return (defaults - replaced) + overrides
    }

    fun usesReference(options: Map<String, String>) =
        "ip" !in options && ("instruction" !in options || options["use_reference"] == "true")

    fun validate(options: Map<String, String>): Map<String, String> {
        val unknown = options.keys - keys
        require(unknown.isEmpty()) { "Unknown Ming synthesis options: ${unknown.joinToString()}" }
        for ((key, allowed) in choices) {
            options[key]?.let { require(it in allowed) { "Ming $key must be one of ${allowed.joinToString()}." } }
        }
        require(groups.count { fields -> fields.any { it in options } } <= 1) { "Choose one Ming speech control group." }
        require("use_reference" !in options || "instruction" in options) { "Ming use_reference requires an instruction." }
        options["instruction"]?.let { require(it.isNotBlank()) { "Ming instruction must not be blank." } }
        val seed = options["seed"] ?: return options
        val number = seed.toIntOrNull()
        require(number != null && number >= 0) { "Ming seed must be an integer from 0 to ${Int.MAX_VALUE}." }
        return options + ("seed" to number.toString())
    }

    /** Called by the private worker with options accepted by the host provider. */
    fun applyTo(task: JSONObject, values: Map<String, String>) {
        values["seed"]?.let { task.put("seed", it.toInt()) }
        val emotion = values["emotion"]
        val dialect = values["dialect"]
        val instruction = values["instruction"]
        val ip = values["ip"]
        if (ip != null) {
            task.put("task", "IP").put("controls", JSONObject().put("ip", ip))
        } else if (emotion != null) {
            task.put("task", "EMOTION").put("controls", JSONObject().put("emotion", emotions.getValue(emotion)))
        } else if (dialect != null) {
            task.put("task", "DIALECT").put("controls", JSONObject().put("dialect", dialect)).remove("language")
        } else if (instruction != null) {
            task.put("task", "FREEFORM").put("instruction", instruction.trim())
        } else if (basic.any { it in values }) {
            val controls = JSONObject()
            values["speed"]?.let { controls.put("speed", speeds.getValue(it)) }
            values["pitch"]?.let { controls.put("pitch", pitches.getValue(it)) }
            values["volume"]?.let { controls.put("volume", volumes.getValue(it)) }
            task.put("task", "BASIC").put("controls", controls)
        }
    }
}
