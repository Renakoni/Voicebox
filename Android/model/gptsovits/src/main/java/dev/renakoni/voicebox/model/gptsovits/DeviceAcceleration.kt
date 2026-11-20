package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.openBackendAsset

import dev.renakoni.voicebox.bridge.AccelerationInfo

import android.content.Context
import dev.renakoni.voicebox.core.DeviceFacts
import android.util.Log
import java.io.File

/** Owned by the service; detection and model work run on the same worker. */
class DeviceAcceleration(private val context: Context) {
    private val capabilityCache = File(context.noBackupFilesDir, "gptsovits-npu-capability")
    val runtimeDirectory = File(context.filesDir, "QualcommRuntime")

    fun detect(): AccelerationInfo {
        val facts = DeviceFacts.read(context)
        val qualcomm = isQualcomm(facts.manufacturer, facts.hardware)
        val device = listOf(if (qualcomm) "Qualcomm" else facts.manufacturer, facts.soc)
            .filter { it.isNotBlank() && it != android.os.Build.UNKNOWN }.joinToString(" · ")
            .ifBlank { facts.model }
        fun unavailable(reason: String, detail: String = "npu_unavailable"): AccelerationInfo {
            Log.i("VoiceBoxAcceleration", reason)
            return AccelerationInfo(device, detail = detail)
        }
        if (!qualcomm || facts.abi != "arm64-v8a") return unavailable("未检测到兼容的高通 NPU")
        val runtime = runtimeDirectory
        if (!hasRuntimeFiles(runtime)) return unavailable("未安装 NPU 加速组件", "npu_runtime_missing")
        val updatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val key = capabilityCacheKey(facts.fingerprint + ":" + facts.abi, updatedAt, runtime)
        val available = cachedCapability(capabilityCache, key) {
            val probe = File(context.cacheDir, "qnn-capability.onnx")
            try {
                Log.i("VoiceBoxAcceleration", "验证 NPU 能力")
                context.openBackendAsset("device-acceleration/probe.onnx").use { input -> probe.outputStream().use(input::copyTo) }
                QnnSession.probe(runtime, probe)
                true
            } catch (error: Exception) {
                Log.w("VoiceBoxAcceleration", "NPU capability probe failed", error)
                false
            } catch (error: LinkageError) {
                Log.w("VoiceBoxAcceleration", "NPU runtime could not load", error)
                false
            } finally { probe.delete() }
        }
        return if (available) AccelerationInfo(device, true, "npu_available")
            else unavailable("NPU capability check failed", "npu_probe_failed")
    }

    companion object {
        internal fun capabilityCacheKey(system: String, appUpdatedAt: Long, runtime: File): String =
            "$system\n$appUpdatedAt\n" + runtime.listFiles().orEmpty().filter { it.isFile && it.extension == "so" }
                .sortedBy { it.name }.joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" }

        internal fun cachedCapability(cache: File, key: String, probe: () -> Boolean): Boolean {
            if (runCatching { cache.readText() }.getOrNull() == key) return true
            return probe().also { available ->
                // An interrupted or unwritable cache only causes a fresh probe next time.
                if (available) runCatching { cache.writeText(key) }
            }
        }

        internal fun isQualcomm(manufacturer: String, hardware: String): Boolean =
            manufacturer.contains("qualcomm", ignoreCase = true) || manufacturer.equals("qti", ignoreCase = true) ||
                manufacturer.equals("qcom", ignoreCase = true) ||
                hardware.equals("qcom", ignoreCase = true)

        internal fun hasRuntimeFiles(directory: File): Boolean {
            if (!listOf("libonnxruntime-qnn.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so")
                    .all { File(directory, it).isFile }) return false
            return directory.listFiles().orEmpty().any { file ->
                file.isFile && file.name.matches(Regex("libQnnHtpV[0-9]+Stub\\.so")) &&
                    File(directory, file.name.replace("Stub.so", "Skel.so")).isFile
            }
        }
    }
}
