package dev.renakoni.voicebox.model.ming

import android.content.Context
import android.util.Log
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.*
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONObject

/** Runs inside the shared private worker, using the task classes from this package. */
internal class MingBackendRuntime(private val context: Context, override val backend: ExecutionBackend,
    private val directory: File, private val work: File, private val allowNpu: Boolean) : BackendRuntime {
    private var usingHtp = false
    override val runtimeLabel get() = "Ming-TTS · ${if (usingHtp) "CPU + GPU + NPU" else backend.label}"

    override fun generate(request: GenerationRequest, callbacks: BackendRuntimeCallbacks): GeneratedAudioFile {
        val resources = MingModelResources(context)
        requireUi(request.auxiliaryReferenceAudioPaths.isEmpty()) { UiText("ming_unsupported_options") }
        callbacks.onStage(GenerationStage.PREPARING_FEATURES, UiText("ming_preparing").resolve(context.resources))
        val bundle = File(work, "bundle").also { check(it.mkdir()) }
        directory.listFiles().orEmpty().filter { it.extension == "so" || it.name == "qnn" }.forEach {
            Files.createSymbolicLink(File(bundle, it.name).toPath(), it.toPath())
        }
        for (name in listOf("models", "reference", "voice")) {
            val source = File(resources.directory, name)
            if (source.isDirectory) Files.createSymbolicLink(File(bundle, name).toPath(), source.toPath())
        }
        val gpu = backend == ExecutionBackend.WEBGPU
        val htp = if (gpu && allowNpu) resources.htp else null
        if (htp != null) {
            val cached = MingHtp.contextGraph(context, directory, htp, work)
            Files.createSymbolicLink(File(bundle, "flow-context.onnx").toPath(), cached.toPath())
            Files.createSymbolicLink(File(bundle, "htp").toPath(), htp.toPath())
            usingHtp = true
        }
        val reference = File(request.referenceAudioPath)
        val default = resources.reference()
        val fixed = default != null && reference.canonicalFile == default.audioFile.canonicalFile && request.referenceText == default.referenceText
        val task = JSONObject().put("task", if (fixed) "TTS" else "CLONE").put("text", request.synthesisText).put("language", request.language)
        if (!fixed) {
            val audio = runCatching { WavAudioReader.readMonoPcm(reference) }.getOrElse {
                requireNotNull(MediaAudioDecoder.decodeMonoPcm(reference)) { "Cannot decode reference audio" }
            }
            val wav = File(work, "reference.wav")
            WavFileWriter.writeMonoPcm16(wav, audio.samples, audio.sampleRate)
            task.put("reference_wav", wav.absolutePath).put("reference_text", request.referenceText)
        }
        val input = File(work, "request.json").apply { writeText(task.toString()) }
        val output = File(work, "output")
        val mode = if (usingHtp) "htp" else if (gpu) "webgpu" else "cpu"
        val threads = if (mode == "webgpu") "6" else "4"
        callbacks.onStage(GenerationStage.RUNNING_INFERENCE, UiText("ming_generating").resolve(context.resources))
        val entry = javaClass.classLoader!!.loadClass("dev.ming.local.Synthesis").getMethod("run", Array<String>::class.java)
        try {
            entry.invoke(null, arrayOf(bundle.absolutePath, input.absolutePath, output.absolutePath, mode, threads))
        } catch (error: java.lang.reflect.InvocationTargetException) {
            output.walkTopDown().filter { it.isFile && it.extension == "log" }.forEach { log ->
                java.io.RandomAccessFile(log, "r").use { reader ->
                    reader.seek((reader.length() - 4096).coerceAtLeast(0))
                    val bytes = ByteArray((reader.length() - reader.filePointer).toInt())
                    reader.readFully(bytes)
                    Log.e("MingWorker", "${log.name}: ${bytes.toString(Charsets.UTF_8)}")
                }
            }
            throw error.targetException
        }
        val sampleRate = 44100
        val duration = JSONObject(File(output, "result.json").readText()).getDouble("audio_seconds")
        val samples = (duration * sampleRate).roundToInt()
        val wav = File(output, "output.wav")
        check(samples > 0 && wav.length() == 44L + 2L * samples) { "Incomplete Ming WAV" }
        val timing = File(output, "speech/native.log").useLines { lines ->
            lines.filter { it.startsWith("{\"paragraph\":") }.map { JSONObject(it) }.toList()
        }
        val inference = timing.sumOf { it.getDouble("inference_seconds") }
        check(inference.isFinite() && inference > 0) { "Missing Ming inference timing" }
        Log.i("MingWorker", "mode=$mode batch=${timing.first().getInt("batch")} inference_seconds=$inference " +
            "audio_seconds=$duration rtf=${inference / duration}")
        val destination = File(OutputStorage(requireNotNull(context.getExternalFilesDir(null))).outputRoot(), "ming-${UUID.randomUUID()}.wav")
        Files.move(wav.toPath(), destination.toPath())
        return GeneratedAudioFile(destination, samples, sampleRate, inference)
    }

    override fun close() = Unit
}
