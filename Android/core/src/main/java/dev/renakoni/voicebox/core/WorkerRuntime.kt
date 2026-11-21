package dev.renakoni.voicebox.core

import android.content.*
import android.os.*
import dev.renakoni.voicebox.bridge.*
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID
import java.util.concurrent.*

/** Called on the inference owner thread. Binder callbacks only enqueue events for that thread. */
internal class WorkerRuntime(private val context: Context, private val id: String, override val backend: ExecutionBackend,
    private val settings: Bundle, private val retain: Boolean) : BackendRuntime {
    private var session: WorkerBinding? = null
    override var runtimeLabel = backend.label
        private set

    override fun generate(request: GenerationRequest, callbacks: BackendRuntimeCallbacks): GeneratedAudioFile {
        val result = call(WorkerProtocol.GENERATE, request, callbacks)
        return GeneratedAudioFile(File(requireNotNull(result.getString("path"))), result.getInt("samples"),
            result.getInt("rate"), result.getDouble("inferenceSeconds").takeIf { it.isFinite() && it > 0 })
    }

    override fun warmup(request: GenerationRequest) { call(WorkerProtocol.WARMUP, request, null) }
    override fun trimMemory() {
        val binding = session ?: return
        if (binding.dead) close() else call(WorkerProtocol.TRIM, null, null)
    }

    fun probe(): Bundle = call(WorkerProtocol.PROBE, null, null)

    fun operate(action: LibraryAction, arguments: Bundle, context: GenerationContext, progress: (String, Int?) -> Unit): Bundle =
        call(WorkerProtocol.LIBRARY, null, object : BackendRuntimeCallbacks {
            override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) =
                progress(message, progressPercent)
        }, Bundle().apply { putString("action", action.name); putBundle("arguments", arguments) }, context)

    private fun call(what: Int, request: GenerationRequest?, callbacks: BackendRuntimeCallbacks?,
        arguments: Bundle = Bundle(), cancellation: GenerationContext? = request?.context): Bundle {
        if (session?.dead == true) close()
        val binding = session ?: WorkerBinding(context).also { session = it }
        var ok = false
        try {
            val data = (request?.encode() ?: arguments).apply {
                putString("session", binding.id); putString("backendId", id); putString("execution", backend.name)
                putBundle("settings", settings)
                if (request == null) putString("request", cancellation?.requestId ?: UUID.randomUUID().toString())
            }
            val result = binding.call(what, data, cancellation, callbacks)
            runtimeLabel = result.getString("runtime") ?: backend.label
            ok = true
            return result
        } finally { if (!ok || !retain) close() }
    }

    override fun close() {
        val binding = session
        session = null
        binding?.close()
    }
}

private class WorkerBinding(private val context: Context) : AutoCloseable {
    val id = UUID.randomUUID().toString()
    private val connected = CompletableFuture<Messenger>()
    private val stopped = CountDownLatch(1)
    private val responses = LinkedBlockingQueue<Bundle>()
    @Volatile private var remote: Messenger? = null
    val dead get() = stopped.count == 0L || remote?.binder?.isBinderAlive == false
    private val receiver = Messenger(Handler(events.looper) { message ->
        responses.offer(message.data.apply { putInt("type", message.what) })
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (connected.isDone) return
            val messenger = Messenger(binder)
            try {
                binder.linkToDeath({ stopped.countDown(); disconnected() }, 0)
            } catch (_: RemoteException) { stopped.countDown(); disconnected(); return }
            remote = messenger
            connected.complete(messenger)
        }
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
    }
    init {
        check(context.bindService(Intent(context, BackendWorkerService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, { events.post(it) }, connection)) { "Cannot bind backend worker" }
    }

    private fun disconnected() {
        connected.completeExceptionally(IllegalStateException("Backend worker disconnected"))
        responses.offer(Bundle().apply { putBoolean("disconnected", true) })
    }

    fun call(what: Int, data: Bundle, cancellation: GenerationContext?, callbacks: BackendRuntimeCallbacks?): Bundle {
        val cooperative = what == WorkerProtocol.LIBRARY
        fun cancelOperation() {
            runCatching { remote?.send(Message.obtain(null, WorkerProtocol.CANCEL).apply {
                this.data = Bundle().apply { putString("session", id); putString("request", data.getString("request")) }
            }) }
        }
        val hook = cancellation?.onCancel {
            connected.completeExceptionally(GenerationCancelledException(cancellation.cancellationReason ?: "CANCELLED"))
            if (cooperative) cancelOperation() else {
                responses.offer(Bundle().apply { putBoolean("cancelled", true) })
            }
        }
        try {
            cancellation?.checkCancelled()
            val worker = connected.get(30, TimeUnit.SECONDS)
            cancellation?.checkCancelled()
            worker.send(Message.obtain(null, what).apply { this.data = data; replyTo = receiver })
            if (cooperative && cancellation?.cancellationReason != null) cancelOperation()
            val timeout = if (what == WorkerProtocol.PROBE) TimeUnit.SECONDS.toMillis(30) else TimeUnit.MINUTES.toMillis(15)
            var deadline = SystemClock.elapsedRealtime() + timeout
            while (true) {
                val item = responses.poll((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    ?: throw TimeoutException("Backend worker timed out")
                if (!cooperative || item.getBoolean("disconnected")) cancellation?.checkCancelled()
                check(!item.getBoolean("disconnected")) { "Backend worker disconnected" }
                if (item.getString("request") != data.getString("request")) continue
                if (item.getInt("type") == WorkerProtocol.EVENT) {
                    if (cooperative) deadline = SystemClock.elapsedRealtime() + timeout
                    callbacks?.onStage(GenerationStage.valueOf(item.getString("stage")!!), item.getString("message").orEmpty(),
                        if (item.containsKey("percent")) item.getInt("percent") else null, item.getString("label"))
                } else {
                    item.getString("error")?.let {
                        cancellation?.checkCancelled()
                        throw item.workerFailure(it)
                    }
                    // A successful library result has already published the operation's files.
                    return item
                }
            }
        } catch (error: ExecutionException) {
            cancellation?.checkCancelled()
            throw error.cause ?: error
        } finally { hook?.close() }
    }

    override fun close() {
        val messenger = remote
        runCatching { messenger?.send(Message.obtain(null, WorkerProtocol.CLOSE).apply {
            data = Bundle().apply { putString("session", id) }
        }) }
        context.unbindService(connection)
        if (messenger == null || stopped.await(10, TimeUnit.SECONDS)) {
            val work = File(context.cacheDir, "bridge-workers/$id")
            deleteFileTree(work)
        } else error("Backend worker did not exit")
    }

    private companion object {
        val events = Handler(HandlerThread("VoiceBox worker IPC").apply { start() }.looper)
    }
}

/** Delete the tree without following symbolic links to files outside it. */
fun deleteFileTree(root: File) {
    val tree = try { Files.walk(root.toPath()) } catch (_: NoSuchFileException) { return }
    tree.use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
