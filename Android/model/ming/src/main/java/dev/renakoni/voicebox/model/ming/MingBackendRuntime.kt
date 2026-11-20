package dev.renakoni.voicebox.model.ming

import dev.renakoni.voicebox.bridge.BackendRuntime
import dev.renakoni.voicebox.bridge.BackendRuntimeCallbacks
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GeneratedAudioFile
import dev.renakoni.voicebox.bridge.GenerationCancelledException
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.MediaAudioDecoder
import dev.renakoni.voicebox.core.WavAudioReader
import dev.renakoni.voicebox.core.WavFileWriter

import dev.renakoni.voicebox.core.OutputStorage
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dev.renakoni.voicebox.bridge.BackendApiContract as Api
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.core.i18n.requireUi
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Adapts Ming to the same request, cancellation and WAV result used by GPT-SoVITS. */
class MingBackendRuntime(private val context: Context, override val backend: ExecutionBackend) : BackendRuntime {
    override val runtimeLabel get() = "Ming-TTS · ${backend.label}"

    override fun generate(request: GenerationRequest, callbacks: BackendRuntimeCallbacks): GeneratedAudioFile {
        val repository = MingPackageRepository(context)
        requireUi(repository.supported && repository.installed) { UiText("ming_not_installed") }
        val modelResources = MingModelResources(context)
        require(modelResources.available) { UiText("model_incomplete").resolve(context.resources) }
        requireUi(request.auxiliaryReferenceAudioPaths.isEmpty() && request.options.isEmpty()) {
            UiText("ming_unsupported_options")
        }
        request.context.checkCancelled()
        callbacks.onStage(GenerationStage.PREPARING_FEATURES, UiText("ming_preparing").resolve(context.resources))
        val work = File(context.cacheDir, "ming-requests/${UUID.randomUUID()}").apply { mkdirs() }
        val connected = CompletableFuture<Messenger>()
        val reply = CompletableFuture<Bundle>()
        val remote = AtomicReference<Messenger?>()
        val stopped = CountDownLatch(1)
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == Api.MSG_STATE_CHANGED) reply.complete(message.data)
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try { binder.linkToDeath({ failed(); stopped.countDown() }, 0) }
                catch (_: android.os.RemoteException) { failed(); stopped.countDown(); return }
                val messenger = Messenger(binder)
                remote.set(messenger)
                connected.complete(messenger)
            }
            override fun onServiceDisconnected(name: ComponentName) = failed()
            override fun onBindingDied(name: ComponentName) = failed()
            override fun onNullBinding(name: ComponentName) = failed()
            private fun failed() {
                val error = IllegalStateException(UiText("ming_worker_failed").resolve(context.resources))
                connected.completeExceptionally(error)
                reply.completeExceptionally(error)
            }
        }
        var bound = false
        var cancellation: AutoCloseable? = null
        try {
            cancellation = request.context.onCancel {
                val error = GenerationCancelledException(request.context.cancellationReason ?: "CANCELLED")
                connected.completeExceptionally(error)
                reply.completeExceptionally(error)
                runCatching { remote.get()?.send(Message.obtain(null, Api.MSG_UNREGISTER_CLIENT)) }
            }
            // Reuse the host audio decoder so imported MP3/M4A references also work with Ming's WAV encoder.
            val reference = File(request.referenceAudioPath)
            val default = modelResources.reference()
            val useDefault = default != null && reference.canonicalFile == default.audioFile.canonicalFile && request.referenceText == default.referenceText
            val wav = if (useDefault) "" else {
                val audio = runCatching { WavAudioReader.readMonoPcm(reference) }.getOrElse {
                    requireNotNull(MediaAudioDecoder.decodeMonoPcm(reference)) { "Cannot decode reference audio" }
                }
                File(work, "reference.wav").also { WavFileWriter.writeMonoPcm16(it, audio.samples, audio.sampleRate) }.absolutePath
            }
            request.context.checkCancelled()
            bound = context.bindService(Intent(context, MingWorkerService::class.java), connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            check(bound) { UiText("ming_worker_failed").resolve(context.resources) }
            val messenger = connected.get(30, TimeUnit.SECONDS)
            request.context.checkCancelled()
            val data = Bundle().apply {
                putString(Api.KEY_BACKEND, backend.name)
                putString(Api.KEY_SYNTHESIS_TEXT, request.synthesisText)
                putString(Api.KEY_LANGUAGE, request.language)
                putString(Api.KEY_REFERENCE_AUDIO_PATH, wav)
                putString(Api.KEY_REFERENCE_TEXT, request.referenceText)
                putString(MingWorkerService.KEY_WORK_DIRECTORY, work.absolutePath)
            }
            callbacks.onStage(GenerationStage.RUNNING_INFERENCE, UiText("ming_generating").resolve(context.resources))
            messenger.send(Message.obtain(null, Api.MSG_SYNTHESIZE).apply { this.data = data; replyTo = receiver })
            val result = reply.get(15, TimeUnit.MINUTES)
            request.context.checkCancelled()
            check(result.getString(Api.KEY_STAGE) == GenerationStage.COMPLETED.name) {
                result.getString(Api.KEY_MESSAGE) ?: UiText("ming_worker_failed").resolve(context.resources)
            }
            val output = File(work, "output/output.wav")
            val audio = WavAudioReader.readMonoPcm(output)
            require(audio.sampleRate == 44100 && audio.samples.isNotEmpty()) { "Invalid Ming audio result" }
            val destination = File(OutputStorage(requireNotNull(context.getExternalFilesDir(null))).outputRoot(), "ming-${UUID.randomUUID()}.wav")
            destination.parentFile!!.mkdirs()
            Files.move(output.toPath(), destination.toPath())
            return GeneratedAudioFile(destination, audio.samples.size, audio.sampleRate)
        } finally {
            cancellation?.close()
            runCatching { remote.get()?.send(Message.obtain(null, Api.MSG_UNREGISTER_CLIENT)) }
            if (bound) context.unbindService(connection)
            // Never delete a directory while a native worker could still be writing into it.
            if (remote.get() == null || stopped.await(10, TimeUnit.SECONDS)) {
                // Files.walk does not follow the bundle's links into the voice library.
                Files.walk(work.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    override fun close() = Unit // The worker is released after each request, including cancellation.
}
