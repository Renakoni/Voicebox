package dev.renakoni.voicebox.model.qwen3

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import com.qwen3.android.Engine
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.DeviceFacts
import dev.renakoni.voicebox.core.MediaAudioDecoder
import dev.renakoni.voicebox.core.OutputStorage
import dev.renakoni.voicebox.core.WavFileWriter
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.model.qwen3.checkpoint.QwenCheckpointVoices
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class QwenRuntime(private val context: Context, private val directory: File, private val work: File,
    private val models: QwenResources, private val official: ModelRef, private val imported: QwenCheckpointVoices,
    override val backend: ExecutionBackend, private val allowNpu: Boolean) : BackendRuntime {
    private var handle = 0L
    private var activeTalker: File? = null
    private val gpu = backend == ExecutionBackend.WEBGPU
    private val codecDevice = if (!gpu) "" else if (allowNpu) "Vulkan0+HTP0" else "Vulkan0"
    override val runtimeLabel = if (!gpu) "CPU" else if (allowNpu) "CPU + Vulkan + Hexagon v${DeviceFacts.hexagonArch}" else "CPU + Vulkan"

    private fun open(talker: File) {
        if (handle != 0L && activeTalker == talker) return
        close()
        val library = if (gpu) {
            QwenDevices.configure(context, directory, allowNpu)
            QwenDevices.acceleratedLibrary(directory)
        } else File(directory, "cpu/libqwen3_jni.so")
        System.load(library.absolutePath)
        System.load(File(directory, "libqwen3_checkpoint.so").absolutePath)
        handle = Engine.open(talker.absolutePath.toByteArray(), models.codec.absolutePath.toByteArray(),
            "CPU".toByteArray(), codecDevice.toByteArray())
        check(handle != 0L) { "Could not open Qwen GGUF models" }
        activeTalker = talker
    }

    private fun reference(path: String): String {
        if (path.isBlank()) return ""
        val file = File(path)
        require(file.isFile) { "Reference audio not found" }
        if (file.extension.equals("wav", true)) return path
        val audio = requireNotNull(MediaAudioDecoder.decodeMonoPcm(file)) { "Cannot decode reference audio" }
        val wav = File(work, "reference.wav")
        WavFileWriter.writeMonoPcm16(wav, audio.samples, audio.sampleRate)
        return wav.absolutePath
    }

    private fun detectedLanguage(text: String): String? {
        return try {
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
            val result = classifier.detectLanguage(TextLanguage.Request.Builder(text.take(512)).build())
            if (result.localeHypothesisCount == 0) return null
            val locale = result.getLocale(0)
            if (result.getConfidenceScore(locale) >= 0.9f) qwenLanguage(locale.toLanguageTag()) else null
        } catch (error: RuntimeException) {
            Log.w("QwenWorker", "Reference language detection unavailable", error)
            null
        }
    }

    override fun generate(request: GenerationRequest, callbacks: BackendRuntimeCallbacks): GeneratedAudioFile {
        request.context.checkCancelled()
        require(request.synthesisText.isNotBlank() && request.auxiliaryReferenceAudioPaths.isEmpty())
        require(request.referenceText.isBlank() || request.referenceAudioPath.isNotBlank())
        var language = requireNotNull(qwenLanguage(request.language)) { "Unsupported Qwen language: ${request.language}" }
        var transcript = request.referenceText.trim()
        val source = transcript.takeIf { it.isNotEmpty() }?.let(::detectedLanguage)
        if (source != null) {
            val target = if (language == "auto") detectedLanguage(request.synthesisText) else language
            if (target != null && target != source) {
                // Keep the speaker embedding without continuing the reference language through ICL.
                language = target
                transcript = ""
            }
        }
        val options = QwenSynthesisOptions(request.options)
        val voice = if (request.characterModel.ref == official) null else imported.find(request.characterModel)
        val speaker = voice?.speaker
        require(speaker == null || request.referenceAudioPath.isBlank()) { "CustomVoice uses its trained speaker and takes no reference audio" }
        callbacks.onStage(GenerationStage.INITIALIZING_BACKEND, UiText("prototype_stage_initializing_backend", runtimeLabel).resolve(context.resources))
        val loadStart = SystemClock.elapsedRealtime()
        open(voice?.weights ?: models.talker)
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        val reference = reference(request.referenceAudioPath)
        val scratch = File(work, "qwen.wav")
        callbacks.onStage(GenerationStage.RUNNING_INFERENCE, UiText("stage_generating").resolve(context.resources))
        val stream = request.context.streamDirectory?.let(::File)
        val start = SystemClock.elapsedRealtimeNanos()
        var samples = 0
        var chunks = 0
        var firstPcmMs = 0L
        val elapsed: Long
        RandomAccessFile(scratch, "rw").use { file ->
            file.setLength(0)
            file.seek(44)
            val sink = object : Engine.Sink {
                override fun isCancelled() = request.context.cancellationReason != null
                override fun receive(pcm: FloatArray): Boolean {
                    if (isCancelled()) return false
                    if (pcm.isEmpty()) return true
                    if (chunks == 0) firstPcmMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                    val data = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    data.asFloatBuffer().put(pcm)
                    file.write(data.array())
                    if (stream != null) {
                        val temp = File(stream, "fragment.tmp")
                        temp.writeBytes(WavFileWriter.toPcm16LeBytes(pcm))
                        Files.move(temp.toPath(), File(stream, "$chunks.pcm").toPath(), StandardCopyOption.ATOMIC_MOVE)
                    }
                    chunks++
                    samples += pcm.size
                    return true
                }
            }
            val ok = Engine.synthesizeConfigured(handle, request.synthesisText.toByteArray(), language.toByteArray(),
                reference.toByteArray(), transcript.toByteArray(), speaker?.toByteArray(),
                options.doSample, options.temperature, options.topK, options.topP, options.repetitionPenalty,
                options.subtalkerDoSample, options.subtalkerTemperature, options.subtalkerTopK, options.subtalkerTopP,
                options.maxNewTokens, options.seed, sink)
            elapsed = SystemClock.elapsedRealtimeNanos() - start
            request.context.checkCancelled()
            check(ok && samples > 0) { "Qwen did not produce audio" }
            // Preserve the experiment's IEEE float32 output for later sample-level comparison.
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + samples * 4).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(3).putShort(1).putInt(24000).putInt(96000).putShort(4).putShort(32)
                .put("data".toByteArray()).putInt(samples * 4)
            file.seek(0)
            file.write(header.array())
        }
        val output = File(OutputStorage(requireNotNull(context.getExternalFilesDir(null))).outputRoot(), "qwen3-${UUID.randomUUID()}.wav")
        Files.move(scratch.toPath(), output.toPath())
        val seconds = elapsed / 1e9
        Log.i("QwenWorker", "mode=$runtimeLabel seed=${options.seed} load_ms=$loadMs first_pcm_ms=$firstPcmMs chunks=$chunks samples=$samples inference_seconds=$seconds")
        return GeneratedAudioFile(output, samples, 24000, seconds)
    }

    override fun trimMemory() = close()
    override fun close() {
        if (handle != 0L) Engine.close(handle)
        handle = 0L
        activeTalker = null
    }
}
