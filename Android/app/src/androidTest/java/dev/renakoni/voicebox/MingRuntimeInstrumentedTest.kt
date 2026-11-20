package dev.renakoni.voicebox

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.renakoni.voicebox.inference.*
import dev.renakoni.voicebox.moremodels.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stage the exact release ZIPs under external-files/ming-release before running on ARM64. */
@RunWith(AndroidJUnit4::class)
class MingRuntimeInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun installed(): MingPackageRepository {
        val repository = MingPackageRepository(app)
        assumeTrue("Requires the runtime's ABI", repository.supported)
        if (!repository.installed) {
            val staged = File(app.getExternalFilesDir(null), "ming-release")
            assumeTrue("Stage the release archives before running", staged.isDirectory)
            // Exercise the production installer with locally staged, SHA-256 verified release bytes.
            MingPackageRepository(repository.directory.parentFile!!, repository.spec) { source, target, cancel, progress ->
                val file = File(staged, source.url.substringAfterLast('/'))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        cancel()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        copied += count
                        progress((copied * 100 / source.bytes).toInt())
                    }
                    assertEquals(source.bytes, copied)
                } }
                assertEquals(source.sha256, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            }.install({}, {})
        }
        assertTrue(repository.installed)
        return repository
    }

    private fun request(text: String) = requireNotNull(MingModelResources(app).reference()).let { reference ->
        GenerationRequest(MingModel.model, "zh", synthesisText = text,
            referenceAudioPath = reference.audioFile.absolutePath, referenceText = reference.referenceText)
    }
    private val callbacks = object : BackendRuntimeCallbacks {
        override fun onStage(stage: GenerationStage, message: String, progressPercent: Int?, progressLabel: String?) = Unit
    }

    @Test fun downloadedRuntimeGeneratesAndClonesThroughExistingContract() {
        installed()
        assertTrue("Push model data separately before running", MingModelResources(app).available)
        assertTrue(ModelAssetRepository(app).discoverCharacterModels().any(MingModel::matches))
        val runtime = MingBackendRuntime(app, ExecutionBackend.WEBGPU)
        val base = request("你好，这是明语音的本地测试。")
        val generated = runtime.generate(base, callbacks)
        assertEquals(44100, generated.sampleRate)
        assertTrue(generated.audioSamples > 44100)
        assertTrue(generated.file.isFile)
        // A different local path forces reference encoding instead of using the fixed voice cache.
        val reference = File(app.cacheDir, "ming-clone-test.wav")
        try {
            File(base.referenceAudioPath).copyTo(reference, overwrite = true)
            val clone = runtime.generate(base.copy(referenceAudioPath = reference.absolutePath,
                synthesisText = "风从窗边轻轻吹过。", context = GenerationContext()), callbacks)
            assertEquals(44100, clone.sampleRate)
            assertTrue(clone.audioSamples > 44100)
            assertTrue("Request cleanup must preserve model data", MingModelResources(app).available)
        } finally { reference.delete() }
    }

    @Test fun cancellationStopsWorkerAndNextRequestCanStart() {
        installed()
        val runtime = MingBackendRuntime(app, ExecutionBackend.CPU)
        val request = request("这是取消任务的验证。".repeat(50))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Unit> { runtime.generate(request, callbacks) }
            Thread.sleep(2500)
            request.context.cancel("TEST_CANCEL")
            runCatching { future.get(20, TimeUnit.SECONDS) }.onSuccess { fail("Cancelled request produced audio") }
            assertTrue("Cancellation must release the worker", future.isDone)
            val result = runtime.generate(request("现在可以继续朗读了。"), callbacks)
            assertTrue(result.audioSamples > 0)
        } finally { pool.shutdownNow() }
    }
}
