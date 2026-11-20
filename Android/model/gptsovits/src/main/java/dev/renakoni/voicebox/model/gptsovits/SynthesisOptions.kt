package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.bridge.GenerationRequest

/** Per-request controls; never part of model/session or reference-feature cache keys. */
data class SynthesisOptions(
    val speed: Double = 1.0,
    val temperature: Float = 1f,
    val topK: Int = 15,
    val topP: Float = 1f,
    val repetitionPenalty: Float = 1.35f,
) {
    init {
        require(speed.isFinite() && speed in 0.6..1.65) { "speed must be between 0.6 and 1.65." }
        require(temperature.isFinite() && temperature in 0f..1f) { "temperature must be between 0 and 1." }
        require(topK in 1..100) { "topK must be between 1 and 100." }
        require(topP.isFinite() && topP in 0f..1f) { "topP must be between 0 and 1." }
        require(repetitionPenalty.isFinite() && repetitionPenalty in 1f..2f) {
            "repetitionPenalty must be between 1 and 2."
        }
    }

    fun samplingValues(): FloatArray = floatArrayOf(temperature, topK.toFloat(), topP, repetitionPenalty)

    companion object {
        val Default = SynthesisOptions()
        private val optionNames = Default.toRequestOptions(500).keys

        fun validateRequestOptions(values: Map<String, String>): Map<String, String> {
            return parse(values).toRequestOptions(decoderSteps(values))
        }

        fun parse(values: Map<String, String?>, defaults: SynthesisOptions = Default): SynthesisOptions {
            require(values.keys.all { it in optionNames }) { "Unknown GPT synthesis parameter." }
            fun float(name: String, fallback: Float): Float = if (name !in values) fallback else
                requireNotNull(values[name]?.toFloatOrNull()) { "$name must be a number." }
            return SynthesisOptions(
                speed = if ("speed" !in values) defaults.speed else
                    requireNotNull(values["speed"]?.toDoubleOrNull()) { "speed must be a number." },
                temperature = float("temperature", defaults.temperature),
                topK = if ("topK" !in values) defaults.topK else
                    requireNotNull(values["topK"]?.toIntOrNull()) { "topK must be an integer." },
                topP = float("topP", defaults.topP),
                repetitionPenalty = float("repetitionPenalty", defaults.repetitionPenalty),
            )
        }
    }
}

fun SynthesisOptions.toRequestOptions(maxDecoderSteps: Int): Map<String, String> = mapOf(
    "maxDecoderSteps" to maxDecoderSteps.toString(),
    "speed" to speed.toString(),
    "temperature" to temperature.toString(),
    "topK" to topK.toString(),
    "topP" to topP.toString(),
    "repetitionPenalty" to repetitionPenalty.toString(),
)

private fun decoderSteps(options: Map<String, String>) = (options["maxDecoderSteps"]?.toInt() ?: 500).also {
    require(it > 0) { "maxDecoderSteps must be positive." }
}

val GenerationRequest.maxDecoderSteps get() = decoderSteps(options)
val GenerationRequest.synthesisOptions get() = SynthesisOptions.parse(options)
