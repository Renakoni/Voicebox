package dev.rinstel.genie_tts.inference

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Two fresh processes, identical fixture: source session, then compile/reload the context. */
@RunWith(AndroidJUnit4::class)
class QnnContextCacheInstrumentedTest {
    @Test fun sourceAndReloadedContextAgree() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = InstrumentationRegistry.getArguments()
        assumeNotNull(arguments.getString("qnnFixture"), arguments.getString("qnnMode"))
        val folder = File(requireNotNull(arguments.getString("qnnFixture")))
        val mode = requireNotNull(arguments.getString("qnnMode"))
        require(mode == "source" || mode == "cold")
        val fixture = JSONObject(File(folder, "fixture.json").readText())
        val frames = fixture.getInt("frames")
        val cache = File(folder, "compiled-context.onnx")
        if (mode == "cold") require(!cache.exists()) { "The cold-path test requires an unused cache path" }
        assertTrue(NativeDecoderLoop.isAvailable())

        // Exercise the existing private JNI boundary without adding a production benchmark API.
        val type = QnnSession::class.java
        fun method(name: String, vararg parameters: Class<*>) =
            type.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
        val create = method("create", String::class.java, String::class.java, String::class.java,
            String::class.java, Int::class.javaPrimitiveType!!)
        val names = method("inputNames", Long::class.javaPrimitiveType!!)
        val run = method("run", Long::class.javaPrimitiveType!!, Array<FloatBuffer>::class.java, FloatBuffer::class.java)
        val destroy = method("destroy", Long::class.javaPrimitiveType!!)
        val result = JSONObject().put("mode", mode).put("frames", frames).put("pss_before_kib", Debug.getPss())
        val started = SystemClock.elapsedRealtime()
        val handle = create.invoke(null, File(context.filesDir, "QualcommRuntime").absolutePath,
            fixture.getString("model"), if (mode == "source") "" else cache.absolutePath, "balanced", frames) as Long
        try {
            result.put("create_ms", SystemClock.elapsedRealtime() - started)
                .put("pss_loaded_kib", Debug.getPss()).put("native_loaded_bytes", Debug.getNativeHeapAllocatedSize())
            @Suppress("UNCHECKED_CAST")
            val inputNames = names.invoke(null, handle) as Array<String>
            val inputs = inputNames.map { name ->
                val bytes = File(folder, "$name.f32").readBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes); rewind() }.asFloatBuffer()
            }.toTypedArray()
            val outputBytes = ByteBuffer.allocateDirect(frames * 1024 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val output = outputBytes.asFloatBuffer()
            val inferenceStarted = SystemClock.elapsedRealtime()
            run.invoke(null, handle, inputs, output)
            result.put("run_ms", SystemClock.elapsedRealtime() - inferenceStarted)
                .put("pss_after_run_kib", Debug.getPss())
            for (i in 0 until output.capacity()) assertTrue("Non-finite output at $i", output.get(i).isFinite())
            File(folder, "$mode-output.f32").outputStream().channel.use { channel ->
                while (outputBytes.hasRemaining()) channel.write(outputBytes)
            }
            if (mode == "cold") {
                val reference = ByteBuffer.wrap(File(folder, "source-output.f32").readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                require(reference.capacity() == output.capacity())
                var error = 0.0
                var power = 0.0
                for (i in 0 until output.capacity()) {
                    val expected = reference.get(i).toDouble()
                    val delta = output.get(i).toDouble() - expected
                    error += delta * delta
                    power += expected * expected
                }
                require(power > 0.0)
                val rms = sqrt(error / power)
                result.put("relative_rms", rms)
                assertTrue("Context serialization changed output: $rms", rms <= 0.00001)
            }
        } finally {
            destroy.invoke(null, handle)
            result.put("pss_after_close_kib", Debug.getPss())
            File(folder, "$mode-result.json").writeText(result.toString(2))
        }
    }
}
