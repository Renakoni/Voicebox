package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit device fixtures; no model assets or benchmark switches in the application. */
@RunWith(AndroidJUnit4::class)
class V5AcousticOptimizationInstrumentedTest {
    private fun folder(): File {
        val path = InstrumentationRegistry.getArguments().getString("acousticFixture")
        assumeNotNull(path)
        return File(requireNotNull(path))
    }

    @Test fun preparesAllShapesOnce() {
        val folder = folder()
        val fixture = JSONObject(File(folder, "fixture.json").readText())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val timings = JSONArray()
        val progress = mutableListOf<Int>()
        val trace = object : InferenceTraceLogger {
            override fun stage(name: String, elapsedMs: Long) {
                timings.put(JSONObject().put("name", name).put("ms", elapsedMs).put("pss_kib", Debug.getPss()))
            }
            override fun event(event: InferenceTraceEvent) = Unit
        }
        val result = JSONObject().put("pss_before_kib", Debug.getPss())
        V5DiTRuntime(OrtEnvironment.getEnvironment(), { error("GPU sessions must remain lazy") }, {},
            File(fixture.getString("directory")), { File(context.filesDir, "QualcommRuntime") }, "balanced", trace,
            { error("NPU unexpectedly unavailable") }).use { dit ->
            dit.prepareNpu { done, total -> assertEquals(3, total); progress += done }
            assertEquals(listOf(0, 1, 2, 3), progress)
            assertEquals(3, timings.length())
            dit.prepareNpu { _, _ -> error("A prepared model must not prepare again") }
            assertEquals(3, timings.length())
            result.put("pss_prepared_kib", Debug.getPss())
            dit.close() // Feature preparation may release active sessions.
            dit.prepareNpu { _, _ -> error("Eviction must preserve prepared disk contexts") }
            V5QnnSession(File(context.filesDir, "QualcommRuntime"),
                File(fixture.getString("directory"), V5DiTRuntime.BODY), "balanced", 768).use { }
            assertEquals(3, timings.length())
        }
        result.put("timings", timings).put("pss_closed_kib", Debug.getPss())
        File(folder, "preparation-result.json").writeText(result.toString(2))
    }

    @Test fun runFixedGraph() {
        val folder = folder()
        val fixture = JSONObject(File(folder, "fixture.json").readText())
        val environment = OrtEnvironment.getEnvironment()
        val inputs = linkedMapOf<String, OnnxTensor>()
        val record = JSONObject()
        val timings = JSONArray()
        val started = SystemClock.elapsedRealtime()
        try {
            val specifications = fixture.getJSONObject("inputs")
            specifications.keys().forEach { name ->
                val shape = specifications.getJSONArray(name).let { a -> LongArray(a.length()) { a.getLong(it) } }
                val bytes = File(folder, "$name.f32").readBytes()
                val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    .apply { put(bytes); rewind() }.asFloatBuffer()
                inputs[name] = OnnxTensor.createTensor(environment, data, shape)
            }
            OrtSession.SessionOptions().use { options ->
                OrtCpuBackend.configureCpuSessionOptions(options)
                options.addWebGPU(emptyMap())
                options.enableProfiling(File(folder, "profile").absolutePath)
                environment.createSession(fixture.getString("graph"), options).use { session ->
                    record.put("load_ms", SystemClock.elapsedRealtime() - started)
                    repeat(4) { trial ->
                        val runStarted = SystemClock.elapsedRealtimeNanos()
                        session.run(inputs.filterKeys { it in session.inputNames }).use { result ->
                            val tensor = result[0] as OnnxTensor
                            val output = tensor.floatBuffer
                            timings.put((SystemClock.elapsedRealtimeNanos() - runStarted) / 1e6)
                            if (trial == 3) {
                                val bytes = ByteBuffer.allocate(output.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN)
                                bytes.asFloatBuffer().put(output)
                                File(folder, "output.f32").writeBytes(bytes.array())
                                val values = bytes.asFloatBuffer()
                                for (i in 0 until values.limit()) assertTrue(values.get(i).isFinite())
                                record.put("shape", JSONArray(tensor.info.shape.toList()))
                            }
                        }
                    }
                    record.put("profile", session.endProfiling())
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
            record.put("run_ms_including_first", timings).put("pss_kib", Debug.getPss())
            File(folder, "graph-result.json").writeText(record.toString(2))
        }
    }
}
