package dev.renakoni.voicebox.model.qwen3

import dev.renakoni.voicebox.bridge.OptionControl

internal class QwenSynthesisOptions(private val values: Map<String, String>) {
    companion object {
        private val temp = OptionControl("temperature", "temperature", "0.9", 0.01f, 2f, 199)
        private val k = OptionControl("top_k", "top_k", "50", 0f, 100f, 100, integral = true)
        private val p = OptionControl("top_p", "top_p", "1.0", 0f, 1f, 100)
        private val penalty = OptionControl("repetition_penalty", "repetition_penalty", "1.05", 0.01f, 2f, 199)
        private val subTemp = temp.copy(name = "subtalker_temperature")
        private val subK = k.copy(name = "subtalker_top_k")
        private val subP = p.copy(name = "subtalker_top_p")
        // The Android Talker has 4096 KV positions shared with the input prompt.
        private val length = OptionControl("max_new_tokens", "qwen_max_new_tokens", "2048", 1f, 4096f, 1, integral = true)
        val controls = listOf(temp, k, p, penalty, subTemp, subK, subP, length)
        private val names = controls.map { it.name }.toSet() + setOf("do_sample", "subtalker_dosample", "seed")
    }

    init {
        for (name in values.keys) require(name in names) { "Unknown Qwen option: $name" }
    }

    private fun enabled(name: String) = (values[name] ?: "true").toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("$name must be true or false")

    private fun number(control: OptionControl, valid: (Float) -> Boolean): Float {
        val value = (values[control.name] ?: control.defaultValue).toFloatOrNull()
        require(value != null && value.isFinite() && valid(value)) { "Invalid Qwen ${control.name}" }
        return value
    }

    private fun integer(control: OptionControl, range: IntRange): Int {
        val value = (values[control.name] ?: control.defaultValue).toIntOrNull()
        require(value != null && value in range) { "Invalid Qwen ${control.name}" }
        return value
    }

    val doSample = enabled("do_sample")
    val temperature = number(temp) { it > 0f }
    val topK = integer(k, 0..Int.MAX_VALUE)
    val topP = number(p) { it in 0f..1f }
    val repetitionPenalty = number(penalty) { it > 0f }
    val subtalkerDoSample = enabled("subtalker_dosample")
    val subtalkerTemperature = number(subTemp) { it > 0f }
    val subtalkerTopK = integer(subK, 0..Int.MAX_VALUE)
    val subtalkerTopP = number(subP) { it in 0f..1f }
    val maxNewTokens = integer(length, length.min.toInt()..length.max.toInt())
    val seed = (values["seed"] ?: "-1").toLongOrNull()?.takeIf { it >= -1L }
        ?: throw IllegalArgumentException("Invalid Qwen seed")
}
