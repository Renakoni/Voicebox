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
import java.nio.FloatBuffer
import kotlin.math.sqrt

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
                File(fixture.getString("directory"), V5DiTRuntime.HTP_BODY), "balanced", 768).use { }
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

    /** Fixed official inputs, independent rolling reference, actual phone backends. */
    @Test fun replayAcousticWaveform() {
        val folder = folder()
        val fixture = JSONObject(File(folder, "fixture.json").readText())
        val directory = File(fixture.getString("directory"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = OrtEnvironment.getEnvironment()
        val sessions = mutableMapOf<String, OrtSession>()
        fun session(name: String) = sessions.getOrPut(name) {
            val file = if (name == V5AcousticRuntime.VOCODER) File(fixture.getString("vocoder")) else File(directory, name)
            OrtSession.SessionOptions().use { options ->
                OrtCpuBackend.configureCpuSessionOptionsForRole(options, InferenceModelRole.VOCODER)
                if (name == V5DiTRuntime.BODY || name == V5AcousticRuntime.VOCODER) options.addWebGPU(emptyMap())
                environment.createSession(file.absolutePath, options)
            }
        }
        fun read(file: File): FloatArray {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val report = JSONArray()
        try {
            V5DiTRuntime(environment, ::session, { sessions.remove(it)?.close() }, directory,
                { File(context.filesDir, "QualcommRuntime") }, "balanced", InferenceTraceLogger.None,
                { error("NPU unexpectedly unavailable") }).use { dit ->
                dit.prepareNpu { _, _ -> }
                for (case in listOf("short", "long")) {
                    val inputDirectory = File(folder, case)
                    val chunks = JSONArray(File(inputDirectory, "chunks.json").readText())
                    var previous: FloatArray? = null
                    var streaming: V5StreamingVocoder? = null
                    var wave = FloatArray(0)
                    try {
                        for (index in 0 until chunks.length()) {
                            val chunk = chunks.getJSONObject(index)
                            val frames = chunk.getInt("frames")
                            val ref = chunk.getInt("reference_frames")
                            val prompt = read(File(inputDirectory, "$index-prompt.f32"))
                            previous?.let { last ->
                                val previousFrames = last.size / 100
                                val tail = minOf(32, ref, previousFrames)
                                for (c in 0 until 100) last.copyInto(prompt, c * frames + ref - tail,
                                    c * previousFrames + previousFrames - tail, (c + 1) * previousFrames)
                            }
                            val features = read(File(inputDirectory, "$index-features.f32"))
                            val state = ByteBuffer.allocateDirect(100 * frames * 4).order(ByteOrder.nativeOrder())
                                .asFloatBuffer().apply { put(read(File(inputDirectory, "$index-x.f32"))); rewind() }
                            OnnxTensor.createTensor(environment, FloatBuffer.wrap(prompt), longArrayOf(1, 100, frames.toLong())).use { p ->
                                OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), longArrayOf(1, 512, frames.toLong())).use { f ->
                                    session("v5-static.onnx").run(mapOf("prompt" to p, "features" to f)).use { result ->
                                        dit.solve(state, result[0] as OnnxTensor, result[1] as OnnxTensor, frames, ref) { }
                                    }
                                }
                            }
                            assertTrue(dit.lastUsedNpu)
                            val count = frames - ref
                            val mel = FloatArray(100 * count)
                            for (c in 0 until 100) for (f in 0 until count) mel[c * count + f] = state.get(c * frames + ref + f)
                            previous = mel
                            val vocoder = session(V5AcousticRuntime.VOCODER)
                            if (chunks.length() > 1) {
                                if (streaming == null) streaming = V5StreamingVocoder(environment, vocoder,
                                    InferenceTimer(InferenceTraceLogger.None), dit.gpuLock)
                                streaming.submit(FloatTensorData(mel, longArrayOf(1, 100, count.toLong())), index == chunks.length() - 1)
                            } else OnnxTensor.createTensor(environment, FloatBuffer.wrap(mel), longArrayOf(1, 100, count.toLong())).use { tensor ->
                                vocoder.run(mapOf("mel" to tensor)).use { result ->
                                    val output = (result[0] as OnnxTensor).floatBuffer
                                    wave = FloatArray(output.remaining()).also { output.get(it) }
                                }
                            }
                        }
                        streaming?.let { wave = it.finish() }
                    } finally { streaming?.close() }
                    val bytes = ByteBuffer.allocate(wave.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asFloatBuffer().put(wave)
                    File(inputDirectory, "phone-wave.f32").writeBytes(bytes.array())
                    val original = read(File(inputDirectory, "official.f32"))
                    assertEquals(original.size, wave.size)
                    var error = 0.0; var energy = 0.0
                    for (i in wave.indices) {
                        val difference = wave[i].toDouble() - original[i]
                        error += difference * difference
                        energy += original[i].toDouble() * original[i]
                    }
                    val rms = sqrt(error / energy)
                    report.put(JSONObject().put("case", case).put("audio_s", wave.size / 48000.0).put("relative_rms", rms))
                    File(folder, "waveform-result.json").writeText(report.toString(2))
                    assertTrue("$case relative waveform RMS=$rms", rms < .02)
                }
            }
        } finally { sessions.values.forEach { it.close() } }
    }
}
