package dev.renakoni.voicebox.core

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import dev.renakoni.voicebox.bridge.*
import dev.renakoni.voicebox.core.i18n.userMessage
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Each binding owns one package and native environment until the process exits. */
class BackendWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var owner: String? = null
    private var provider: BackendProvider? = null
    private var runtime: BackendRuntime? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        val session = data.getString("session")
        val what = message.what
        val reply = message.replyTo
        if (session == null || runCatching { UUID.fromString(session).toString() }.getOrNull() != session) return@Handler true
        if (owner == null && what != WorkerProtocol.CLOSE) {
            owner = session
            try { reply.binder.linkToDeath({ terminate() }, 0) } catch (_: RemoteException) { terminate() }
        }
        if (owner == session && what == WorkerProtocol.CLOSE) terminate()
        else if (owner == session) executor.execute { execute(what, data, reply) }
        true
    })

    override fun onCreate() {
        super.onCreate()
        // Android creates one instance of this non-exported service. Files left by a
        // force-stopped host belong to the previous process, before any new request.
        executor.execute {
            File(cacheDir, "bridge-workers").listFiles().orEmpty().forEach { directory ->
                runCatching { deleteWorkerFiles(directory) }.onFailure { Log.w("VoiceBoxWorker", "Cannot clear previous request", it) }
            }
        }
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(dev.renakoni.voicebox.core.i18n.UiLanguageContext(base))
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { terminate(); return false }

    private fun execute(what: Int, data: Bundle, reply: Messenger) {
        fun send(type: Int, contents: Bundle) {
            contents.putString("request", data.getString("request"))
            reply.send(Message.obtain(null, type).apply { this.data = contents })
        }
        val result = Bundle()
        try {
            val backend = provider ?: run {
                val archive = BridgePackages(this).get(data.getString("backendId")!!)
                val directory = requireNotNull(archive.directory) { "Backend is unavailable" }
                val work = File(cacheDir, "bridge-workers/${data.getString("session")}").also { it.mkdirs() }
                archive.openProvider(this, directory, work, requireNotNull(data.getBundle("settings"))).also { provider = it }
            }
            val engine = runtime ?: backend.createRuntime(ExecutionBackend.valueOf(data.getString("execution")!!)).also { runtime = it }
            when (what) {
                WorkerProtocol.TRIM -> engine.trimMemory()
                WorkerProtocol.WARMUP -> data.decode(backend).let { it.context.run { engine.warmup(it) } }
                WorkerProtocol.GENERATE -> {
                    val request = data.decode(backend)
                    val audio = request.context.run { engine.generate(request, object : BackendRuntimeCallbacks {
                        override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) {
                            send(WorkerProtocol.EVENT, Bundle().apply {
                                putString("stage", stage.name); putString("message", message.take(4096))
                                progressPercent?.let { putInt("percent", it) }; putString("label", progressLabel?.take(4096))
                            })
                        }
                    }) }
                    result.putString("path", audio.file.absolutePath)
                    result.putInt("samples", audio.audioSamples)
                    result.putInt("rate", audio.sampleRate)
                }
                else -> error("Unknown worker operation")
            }
            result.putString("runtime", engine.runtimeLabel)
        } catch (error: Throwable) {
            Log.e("VoiceBoxWorker", "Backend operation failed", error)
            result.putString("error", (error.userMessage(resources) ?: error.message ?: error.javaClass.simpleName).take(4096))
            result.putString("code", (error as? BackendFailure)?.code ?: "BACKEND_ERROR")
        }
        runCatching { send(WorkerProtocol.RESULT, result) }.onFailure { terminate() }
    }

    private fun terminate() { android.os.Process.killProcess(android.os.Process.myPid()) }
}
