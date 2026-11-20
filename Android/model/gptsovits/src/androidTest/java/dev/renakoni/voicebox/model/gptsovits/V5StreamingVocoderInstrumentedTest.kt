package dev.renakoni.voicebox.model.gptsovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V5StreamingVocoderInstrumentedTest {
    @Test fun streamingVocoderMatchesWholeWaveform() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(requireNotNull(InstrumentationRegistry.getArguments().getString("checkpointSource")))
        val bytes = File(source, "streaming-mel.f32").readBytes()
        val mel = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(mel)
        val frames = mel.size / 100
        require(frames > 100 && frames * 100 == mel.size)
        val environment = OrtEnvironment.getEnvironment()
        val graph = File(context.getExternalFilesDir(null), "RuntimeAssets/v5-vocoder/v5-vocoder-subpixel.onnx")
        OrtSession.SessionOptions().use { options ->
            OrtCpuBackend.configureCpuSessionOptions(options)
            options.addWebGPU(emptyMap())
            environment.createSession(graph.absolutePath, options).use { session ->
                val baseline = OnnxTensor.createTensor(environment, FloatBuffer.wrap(mel), longArrayOf(1, 100, frames.toLong())).use { input ->
                    session.run(mapOf("mel" to input)).use { result ->
                        val buffer = (result.get(0) as OnnxTensor).floatBuffer
                        FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
                val actual = V5StreamingVocoder(environment, session, InferenceTimer(InferenceTraceLogger.None)).use { streaming ->
                    var start = 0
                    for (end in listOf(frames / 3, frames * 2 / 3, frames - 1, frames)) {
                        val length = end - start
                        val chunk = FloatArray(100 * length)
                        for (c in 0 until 100) mel.copyInto(chunk, c * length, c * frames + start, c * frames + end)
                        streaming.submit(FloatTensorData(chunk, longArrayOf(1, 100, length.toLong())), last = end == frames)
                        start = end
                    }
                    streaming.finish()
                }
                assertEquals(baseline.size, actual.size)
                var error = 0.0
                var power = 0.0
                for (i in actual.indices) { error += (actual[i] - baseline[i]).toDouble().let { it * it }; power += baseline[i].toDouble().let { it * it } }
                val relativeRms = kotlin.math.sqrt(error / power)
                File(source, "streaming-parity.json").writeText(JSONObject().put("relative_rms", relativeRms)
                    .put("samples", actual.size).put("last_chunk_frames", 1).toString(2))
                assertTrue("Streaming waveform relative RMS: $relativeRms", relativeRms < 0.0005)

                // An interrupted caller must not let the backend release a session still in use.
                val gpuLock = Any()
                val streaming = V5StreamingVocoder(environment, session, InferenceTimer(InferenceTraceLogger.None), gpuLock)
                val closing = CountDownLatch(1)
                val closed = CountDownLatch(1)
                val failure = AtomicReference<Throwable>()
                val interrupted = AtomicReference(false)
                val closer = Thread {
                    Thread.currentThread().interrupt()
                    closing.countDown()
                    try { streaming.close() } catch (error: Throwable) { failure.set(error) }
                    finally { interrupted.set(Thread.currentThread().isInterrupted); closed.countDown() }
                }
                try {
                    synchronized(gpuLock) {
                        streaming.submit(FloatTensorData(mel, longArrayOf(1, 100, frames.toLong())), last = true)
                        closer.start()
                        assertTrue(closing.await(5, TimeUnit.SECONDS))
                        assertFalse("Close returned while the GPU worker was blocked", closed.await(200, TimeUnit.MILLISECONDS))
                    }
                    assertTrue("Vocoder close did not finish", closed.await(60, TimeUnit.SECONDS))
                    assertTrue(failure.get() is InterruptedException)
                    assertTrue(interrupted.get())
                } finally {
                    closer.join()
                    streaming.close()
                }
            }
        }
    }
}
