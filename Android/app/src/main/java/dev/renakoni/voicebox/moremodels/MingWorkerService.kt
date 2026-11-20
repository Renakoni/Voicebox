package dev.renakoni.voicebox.moremodels

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import dev.renakoni.voicebox.BackendApiContract as Api
import dev.renakoni.voicebox.inference.ExecutionBackend
import dev.renakoni.voicebox.inference.GenerationStage
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/** Private, disposable process keeps Ming's native environment and ORT separate from the host. */
class MingWorkerService : Service() {
    companion object { const val KEY_WORK_DIRECTORY = "ming_work_directory" }
    private val worker = Executors.newSingleThreadExecutor()
    private var started = false
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            Api.MSG_UNREGISTER_CLIENT -> Process.killProcess(Process.myPid())
            Api.MSG_SYNTHESIZE -> if (!started) {
                started = true
                val data = Bundle(message.data)
                val reply = message.replyTo
                worker.execute {
                    val result = runCatching { synthesize(data) }
                    result.exceptionOrNull()?.let { Log.e("MingWorker", "Ming task failed", it) }
                    val response = Bundle().apply {
                        putString(Api.KEY_STAGE, if (result.isSuccess) GenerationStage.COMPLETED.name else GenerationStage.ERROR.name)
                        putString(Api.KEY_MESSAGE, result.exceptionOrNull()?.let { (it.cause ?: it).message })
                    }
                    runCatching { reply.send(Message.obtain(null, Api.MSG_STATE_CHANGED).apply { this.data = response }) }
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    private fun synthesize(data: Bundle) {
        val repository = MingPackageRepository(this)
        check(repository.installed && repository.supported)
        val modelResources = MingModelResources(this)
        check(modelResources.available)
        val work = File(requireNotNull(data.getString(KEY_WORK_DIRECTORY))).canonicalFile
        require(work.parentFile == File(cacheDir, "ming-requests").canonicalFile && work.isDirectory)
        // Reuse the upstream bundle layout without copying or packaging the model data.
        val root = File(work, "bundle").apply { mkdirs() }
        repository.spec.files.keys.forEach { name ->
            Files.createSymbolicLink(File(root, name).toPath(), File(repository.directory, name).toPath())
        }
        listOf("models", "reference", "voice", "examples").forEach { name ->
            val source = File(modelResources.directory, name)
            if (source.isDirectory) Files.createSymbolicLink(File(root, name).toPath(), source.toPath())
        }
        val reference = data.getString(Api.KEY_REFERENCE_AUDIO_PATH).orEmpty()
        val request = JSONObject().put("task", if (reference.isEmpty()) "TTS" else "CLONE")
            .put("text", data.getString(Api.KEY_SYNTHESIS_TEXT))
            .put("language", data.getString(Api.KEY_LANGUAGE))
        if (reference.isNotEmpty()) request.put("reference_wav", reference).put("reference_text", data.getString(Api.KEY_REFERENCE_TEXT))
        val input = File(work, "request.json").apply { writeText(request.toString()) }
        val loader = DexClassLoader(File(repository.directory, "tasks.jar").absolutePath, codeCacheDir.absolutePath,
            repository.directory.absolutePath, classLoader)
        val entry = loader.loadClass("dev.ming.local.Synthesis").getMethod("run", Array<String>::class.java)
        val mode = if (data.getString(Api.KEY_BACKEND) == ExecutionBackend.WEBGPU.name) "webgpu" else "cpu"
        try {
            entry.invoke(null, arrayOf(root.absolutePath, input.absolutePath, File(work, "output").absolutePath, mode, "4"))
        } catch (error: ReflectiveOperationException) {
            work.walkTopDown().filter { it.isFile && it.extension == "log" }.forEach { log ->
                java.io.RandomAccessFile(log, "r").use { input ->
                    input.seek((input.length() - 4096).coerceAtLeast(0))
                    val bytes = ByteArray((input.length() - input.filePointer).toInt())
                    input.readFully(bytes)
                    Log.e("MingWorker", "${log.name}: ${bytes.toString(Charsets.UTF_8)}")
                }
            }
            throw error
        }
        val result = JSONObject(File(work, "output/result.json").readText())
        check(result.getBoolean("complete")) { "Ming did not complete synthesis" }
    }
}
