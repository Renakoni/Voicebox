package dev.renakoni.voicebox

import dev.renakoni.voicebox.bridge.BackendRuntimeCallbacks
import dev.renakoni.voicebox.bridge.ExecutionBackend
import dev.renakoni.voicebox.bridge.GenerationContext
import dev.renakoni.voicebox.bridge.GenerationRequest
import dev.renakoni.voicebox.bridge.GenerationStage
import dev.renakoni.voicebox.core.BridgePackage
import dev.renakoni.voicebox.core.BridgePackages

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import dev.renakoni.voicebox.core.copyVerifiedDownload
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stage the exact release ZIPs under external-files/ming-release before running on ARM64. */
@RunWith(AndroidJUnit4::class)
class MingRuntimeInstrumentedTest {
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun installed(): BridgePackage {
        val repository = BridgePackages(app).get("ming")
        assumeTrue("Requires the runtime's ABI", repository.supported)
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("modelFixture")?.let { path ->
            val target = File(app.getExternalFilesDir(null), "CharacterModels/ming/dense-0.5b")
            if (!target.isDirectory) assertTrue(File(path).copyRecursively(target))
        }
        arguments.getString("shellFixture")?.let { fixture ->
            require(fixture.matches(Regex("/sdcard/Download/[A-Za-z0-9_/-]+")))
            val target = File(app.getExternalFilesDir(null), "CharacterModels/ming/dense-0.5b")
            if (!target.isDirectory) {
                val staging = File(target.parentFile, ".fixture-" + java.util.UUID.randomUUID())
                check(staging.mkdirs())
                try {
                    val process = ProcessBuilder("tar", "-xf", "-", "-C", staging.absolutePath).start()
                    val stream = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                        "tar -cf - -C $fixture models voice reference examples")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(stream).use { input ->
                        process.outputStream.use { output -> input.copyTo(output, 262144) }
                    }
                    check(process.waitFor() == 0 && staging.renameTo(target))
                } finally { staging.deleteRecursively() }
            }
        }
        if (!repository.installed) {
            val staged = arguments.getString("packageFixture")?.let(::File) ?: File(app.getExternalFilesDir(null), "ming-release")
            assumeTrue("Stage the release archives before running", staged.isDirectory)
            // Exercise the production installer with locally staged, SHA-256 verified release bytes.
            BridgePackage(File(app.noBackupFilesDir, "model-runtimes/ming"), repository.spec, repository.spec.abi) { source, target, cancel, progress ->
                val file = File(staged, source.url.substringAfterLast('/'))
                file.inputStream().use { copyVerifiedDownload(source, it, target, cancel, progress) }
            }.install({}, {})
        }
        assertTrue(repository.installed)
        return repository
    }

    private val provider get() = (app.applicationContext as VoiceBoxApplication).backends.provider("ming")
    private fun request(text: String) = provider.discoverCharacterModels().single().let { model ->
        val reference = requireNotNull(provider.findReferenceExample(model))
        GenerationRequest(model, "zh", synthesisText = text,
            referenceAudioPath = reference.audioFile.absolutePath, referenceText = reference.referenceText)
    }
    private val callbacks = object : BackendRuntimeCallbacks {
        override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) = Unit
    }

    private fun recordAudio(name: String, result: dev.renakoni.voicebox.bridge.GeneratedAudioFile) {
        val samples = dev.renakoni.voicebox.core.WavAudioReader.readMonoPcm(result.file).samples
        assertTrue("Expected a non-silent waveform", samples.any { kotlin.math.abs(it) > 0.01f })
        android.util.Log.i("MingVerification", "$name ${result.sampleRate} Hz ${result.audioSamples} samples ${result.file}")
        if (InstrumentationRegistry.getArguments().getString("saveAudio") == "true") {
            result.file.copyTo(File(app.cacheDir, "ming-$name.wav"), overwrite = true)
        }
    }

    @Test fun packageLoadsThroughParentContractWithoutWeights() {
        val archive = installed()
        val backend = provider
        assertTrue("zh" in backend.capabilities.languages)
        assertTrue(backend.validateOptions(emptyMap()).isEmpty())
        assertThrows(ClassNotFoundException::class.java) { app.classLoader.loadClass(archive.spec.entry) }
        assertThrows(IllegalArgumentException::class.java) { backend.validateOptions(mapOf("topK" to "12")) }
    }

    @Test fun downloadedRuntimeGeneratesAndClonesThroughExistingContract() {
        installed()
        assertTrue("Push model data separately before running", provider.discoverCharacterModels().isNotEmpty())
        val runtime = provider.createRuntime(ExecutionBackend.valueOf(InstrumentationRegistry.getArguments().getString("execution", "WEBGPU")!!))
        val base = request("你好，这是明语音的本地测试。")
        val generated = runtime.generate(base, callbacks)
        assertEquals(44100, generated.sampleRate)
        assertTrue(generated.audioSamples > 44100)
        val player = android.media.MediaPlayer()
        try {
            player.setDataSource(generated.file.absolutePath)
            player.prepare()
            player.start()
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (player.currentPosition == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
            assertTrue("Playback advances", player.currentPosition > 0)
        } finally { player.release() }
        recordAudio("default", generated)

        // A different local path forces reference encoding instead of using the fixed voice cache.
        val reference = File(app.cacheDir, "ming-clone-test.wav")
        try {
            File(base.referenceAudioPath).copyTo(reference, overwrite = true)
            val clone = runtime.generate(base.copy(referenceAudioPath = reference.absolutePath,
                synthesisText = "风从窗边轻轻吹过。", context = GenerationContext()), callbacks)
            assertEquals(44100, clone.sampleRate)
            assertTrue(clone.audioSamples > 44100)
            recordAudio("clone", clone)

            assertTrue("Request cleanup must preserve model data", provider.discoverCharacterModels().isNotEmpty())
        } finally { reference.delete() }
    }

    @Test fun cancellationStopsWorkerAndNextRequestCanStart() {
        installed()
        val runtime = provider.createRuntime(ExecutionBackend.CPU)
        val request = request("这是取消任务的验证。".repeat(50))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Unit> { runtime.generate(request, callbacks) }
            Thread.sleep(2500)
            request.context.cancel("TEST_CANCEL")
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(20, TimeUnit.SECONDS) }
            assertTrue("Cancellation must reach the client", failure.cause is dev.renakoni.voicebox.bridge.GenerationCancelledException)
            assertTrue("Cancellation must release the worker", future.isDone)
            val result = runtime.generate(request("现在可以继续朗读了。"), callbacks)
            assertTrue(result.audioSamples > 0)
        } finally { pool.shutdownNow() }
    }
}
