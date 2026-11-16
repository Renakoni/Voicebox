package dev.rinstel.genie_tts.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
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
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class V2AcousticOptimizationInstrumentedTest {
    @Test fun matchesOfficialAcousticsAndReloadsCachedContext() {
        val path = InstrumentationRegistry.getArguments().getString("v2AcousticFixture")
        assumeNotNull(path)
        val folder = File(requireNotNull(path))
        val fixture = JSONObject(File(folder, "fixture.json").readText())
        val directory = File(fixture.getString("directory"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = OrtEnvironment.getEnvironment()
        val sessions = mutableMapOf<String, OrtSession>()
        fun session(name: String) = sessions.getOrPut(name) {
            OrtSession.SessionOptions().use { options ->
                OrtCpuBackend.configureCpuSessionOptions(options)
                environment.createSession(File(directory, name).absolutePath, options)
            }
        }
        fun bytes(file: File) = file.readBytes().let { data ->
            ByteBuffer.allocateDirect(data.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(data); rewind() }
        }
        val timings = JSONArray()
        val report = JSONArray()
        val trace = object : InferenceTraceLogger {
            override fun stage(name: String, elapsedMs: Long) { timings.put(JSONObject().put("name", name).put("ms", elapsedMs)) }
            override fun event(event: InferenceTraceEvent) = Unit
        }
        try {
            V2HtpVocoder(environment, ::session, directory, { File(context.filesDir, "QualcommRuntime") },
                "balanced", trace, { error("Unexpected HTP failure") }).use { vocoder ->
                val progress = mutableListOf<Int>()
                vocoder.prepare { done, total -> assertEquals(1, total); progress += done }
                assertEquals(listOf(0, 1), progress)
                vocoder.prepare { _, _ -> error("Repeated source preparation") }
                vocoder.close()
                vocoder.prepare { _, _ -> error("Eviction must retain prepared-cache state") }
                OrtSession.SessionOptions().use { options ->
                    OrtCpuBackend.configureCpuSessionOptions(options)
                    environment.createSession(fixture.getString("encoder"), options).use { encoder ->
                        for (case in listOf("short", "long")) {
                            val item = File(folder, case)
                            val shapes = JSONObject(File(item, "inputs.json").readText())
                            val inputs = mutableMapOf<String, OnnxTensor>()
                            try {
                                shapes.keys().forEach { name ->
                                    val spec = shapes.getJSONObject(name)
                                    val shape = spec.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }
                                    val data = bytes(File(item, spec.getString("file")))
                                    inputs[name] = if (spec.getString("dtype") == "int64")
                                        OnnxTensor.createTensor(environment, data.asLongBuffer(), shape)
                                    else OnnxTensor.createTensor(environment, data.asFloatBuffer(), shape)
                                }
                                encoder.run(inputs).use { encoded ->
                                    val output = requireNotNull(vocoder.generate(encoded.get(0) as OnnxTensor, inputs.getValue("ge")))
                                    assertTrue(vocoder.lastUsedNpu)
                                    val expected = bytes(File(item, "official.f32")).asFloatBuffer()
                                    assertEquals(expected.capacity(), output.audio.size)
                                    var error = 0.0; var energy = 0.0
                                    output.audio.forEachIndexed { index, value ->
                                        val reference = expected.get(index).toDouble()
                                        error += (value - reference) * (value - reference)
                                        energy += reference * reference
                                    }
                                    val rms = sqrt(error / energy)
                                    assertTrue("$case RMS=$rms", rms < .02)
                                    val raw = ByteBuffer.allocate(output.audio.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                                    raw.asFloatBuffer().put(output.audio)
                                    File(item, "phone.f32").writeBytes(raw.array())
                                    report.put(JSONObject().put("case", case).put("relative_rms", rms).put("samples", output.audio.size))
                                }
                            } finally { inputs.values.forEach(OnnxTensor::close) }
                        }
                    }
                }
            }
        } finally { sessions.values.forEach(OrtSession::close) }
        File(folder, "result.json").writeText(JSONObject().put("cases", report).put("timings", timings).toString(2))
    }
}
