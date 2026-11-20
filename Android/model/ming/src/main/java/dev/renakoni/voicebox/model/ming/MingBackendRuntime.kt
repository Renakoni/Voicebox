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
import org.json.JSONObject

/** Runs inside the shared private worker, using the task classes from this package. */
internal class MingBackendRuntime(private val context: Context, override val backend: ExecutionBackend,
    private val directory: File, private val work: File) : BackendRuntime {
    override val runtimeLabel get() = "Ming-TTS · ${backend.label}"

    override fun generate(request: GenerationRequest, callbacks: BackendRuntimeCallbacks): GeneratedAudioFile {
        val resources = MingModelResources(context)
        requireUi(resources.available) { UiText("model_incomplete") }
        requireUi(request.auxiliaryReferenceAudioPaths.isEmpty() && request.options.isEmpty()) { UiText("ming_unsupported_options") }
        callbacks.onStage(GenerationStage.PREPARING_FEATURES, UiText("ming_preparing").resolve(context.resources))
        val bundle = File(work, "bundle").also { check(it.mkdir()) }
        directory.listFiles().orEmpty().filter { it.extension == "so" || it.name == "tasks.jar" }.forEach {
            Files.createSymbolicLink(File(bundle, it.name).toPath(), it.toPath())
        }
        for (name in listOf("models", "reference", "voice", "examples")) {
            val source = File(resources.directory, name)
            if (source.isDirectory) Files.createSymbolicLink(File(bundle, name).toPath(), source.toPath())
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
        val mode = if (backend == ExecutionBackend.WEBGPU) "webgpu" else "cpu"
        callbacks.onStage(GenerationStage.RUNNING_INFERENCE, UiText("ming_generating").resolve(context.resources))
        val entry = javaClass.classLoader!!.loadClass("dev.ming.local.Synthesis").getMethod("run", Array<String>::class.java)
        try {
            entry.invoke(null, arrayOf(bundle.absolutePath, input.absolutePath, output.absolutePath, mode, "4"))
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
        check(JSONObject(File(output, "result.json").readText()).getBoolean("complete")) { "Ming did not complete synthesis" }
        val wav = File(output, "output.wav")
        val audio = WavAudioReader.readMonoPcm(wav)
        require(audio.sampleRate == 44100 && audio.samples.isNotEmpty()) { "Invalid Ming audio result" }
        val destination = File(OutputStorage(requireNotNull(context.getExternalFilesDir(null))).outputRoot(), "ming-${UUID.randomUUID()}.wav")
        Files.move(wav.toPath(), destination.toPath())
        return GeneratedAudioFile(destination, audio.samples.size, audio.sampleRate)
    }

    override fun close() = Unit
}
