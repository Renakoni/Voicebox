package dev.rinstel.genie_tts.qqvoice

import dev.rinstel.genie_tts.R
import dev.rinstel.genie_tts.i18n.UiText
import dev.rinstel.genie_tts.i18n.requireUi

import dev.rinstel.genie_tts.inference.ReferenceAudioResampler
import dev.rinstel.genie_tts.inference.WavAudioReader
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

object QqSilk {
    init { System.loadLibrary("genie_silk") }
    external fun encode(pcm: ShortArray): ByteArray

    data class Encoded(val bytes: ByteArray, val durationMs: Int, val waveform: ByteArray)

    fun fromWav(file: File): Encoded {
        val audio = WavAudioReader.readMonoPcm(file)
        requireUi(audio.samples.isNotEmpty() && audio.samples.size.toLong() <= audio.sampleRate.toLong() * 60) {
            UiText(R.string.qq_duration_limit)
        }
        val samples = ReferenceAudioResampler.resample(audio.samples, audio.sampleRate, 24000)
        requireUi(samples.all(Float::isFinite)) { UiText(R.string.audio_samples_invalid) }
        val pcm = ShortArray(samples.size) { (samples[it] * 32768).roundToInt().coerceIn(-32768, 32767).toShort() }
        val waveform = ByteArray(30) { bin ->
            val start = bin * samples.size / 30
            val end = (bin + 1) * samples.size / 30
            var power = 0.0
            for (i in start until end) power += samples[i].toDouble() * samples[i]
            (sqrt(power / (end - start).coerceAtLeast(1)) * 100).roundToInt().coerceIn(0, 100).toByte()
        }
        return Encoded(encode(pcm), (audio.samples.size * 1000L / audio.sampleRate).toInt(), waveform)
    }
}
