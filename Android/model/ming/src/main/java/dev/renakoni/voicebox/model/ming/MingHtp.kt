package dev.renakoni.voicebox.model.ming

import android.content.Context
import android.os.Build
import android.util.Log
import dev.renakoni.voicebox.bridge.AccelerationInfo
import dev.renakoni.voicebox.core.DeviceFacts
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object MingHtp {
    @JvmStatic private external fun probe(runtime: String, graph: String): Boolean
    @JvmStatic private external fun prepare(runtime: String, source: String, cache: String)

    private fun load(directory: File): File {
        System.load(File(directory, "libming-ort.so").absolutePath)
        System.load(File(directory, "libming-htp.so").absolutePath)
        return File(directory, "qnn").canonicalFile
    }

    fun detect(context: Context, directory: File): AccelerationInfo {
        val facts = DeviceFacts.read(context)
        val device = facts.soc.ifBlank { facts.model }
        if (facts.abi != "arm64-v8a" ||
            !(facts.manufacturer.contains("qualcomm", ignoreCase = true) ||
                facts.manufacturer.equals("qti", ignoreCase = true) ||
                facts.hardware.equals("qcom", ignoreCase = true))) {
            return AccelerationInfo(device, detail = "npu_unavailable")
        }
        val available = runCatching {
            probe(load(directory).absolutePath, File(directory, "provider-probe.onnx").absolutePath)
        }.onFailure { Log.w("MingWorker", "HTP probe failed", it) }.getOrDefault(false)
        return AccelerationInfo(device, available, if (available) "npu_available" else "npu_probe_failed")
    }

    fun contextGraph(context: Context, directory: File, graph: File, work: File): File {
        val source = File(graph, "flow.onnx")
        val weights = File(graph, "flow.data")
        val revision = directory.parentFile!!.name
        val identity = listOf(Build.TIME, source.length(), source.lastModified(), weights.length(), weights.lastModified())
            .joinToString("-")
        val cache = File(context.cacheDir, "ming-htp/$revision-$identity.onnx")
        if (cache.length() > 0) return cache
        val runtime = load(directory)
        val temporary = File(work, "flow.onnx")
        prepare(runtime.absolutePath, source.absolutePath, temporary.absolutePath)
        check(temporary.length() > 0) { "Ming HTP context was not written" }
        cache.parentFile!!.mkdirs()
        Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        cache.parentFile!!.listFiles()?.filter { it != cache }?.forEach { it.delete() }
        return cache
    }
}
