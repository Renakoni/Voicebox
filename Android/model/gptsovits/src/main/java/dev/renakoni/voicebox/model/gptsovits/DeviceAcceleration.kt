package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.openBackendAsset

import dev.renakoni.voicebox.core.i18n.UiText

import android.content.Context
import dev.renakoni.voicebox.core.DeviceFacts
import android.util.Log
import java.io.File

data class DeviceAccelerationStatus(
    val device: String = "",
    val available: Boolean = false,
    val detail: UiText = UiText("npu_checking"),
    val checking: Boolean = true,
)

/** Owned by the service; detection and model work run on the same worker. */
class DeviceAcceleration(private val context: Context) {
    @Volatile var status = DeviceAccelerationStatus()
        private set
    private val capabilityCache = File(context.noBackupFilesDir, "gptsovits-npu-capability")
    @Volatile var runtimeDirectory: File? = null
        private set

    fun detect(webGpu: Boolean) {
        val facts = DeviceFacts.read(context)
        val qualcomm = isQualcomm(facts.manufacturer, facts.hardware)
        val device = listOf(if (qualcomm) "Qualcomm" else facts.manufacturer, facts.soc)
            .filter { it.isNotBlank() && it != android.os.Build.UNKNOWN }.joinToString(" · ")
            .ifBlank { facts.model }
        fun unavailable(reason: String) {
            Log.i("VoiceBoxAcceleration", reason)
            runtimeDirectory = null
            status = DeviceAccelerationStatus(device, detail = UiText("npu_unavailable"), checking = false)
        }
        if (!qualcomm || facts.abi != "arm64-v8a") {
            unavailable("未检测到兼容的高通 NPU")
            return
        }
        if (!webGpu) { unavailable("当前运行库不支持混合加速"); return }
        val runtime = File(context.filesDir, "QualcommRuntime")
        if (!hasRuntimeFiles(runtime)) { unavailable("未安装 NPU 加速组件"); return }
        status = DeviceAccelerationStatus(device)
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
        if (available) {
            runtimeDirectory = runtime
            status = DeviceAccelerationStatus(device, true, UiText("npu_available"), checking = false)
        } else unavailable("NPU capability check failed")
    }

    fun modelUnavailable() {
        capabilityCache.delete()
        runtimeDirectory = null
        status = status.copy(available = false, detail = UiText("npu_unavailable"))
    }

    companion object {
        internal fun capabilityCacheKey(system: String, appUpdatedAt: Long, runtime: File): String =
            "$system\n$appUpdatedAt\n" + runtime.listFiles().orEmpty().filter { it.isFile && it.extension == "so" }
                .sortedBy { it.name }.joinToString("\n") { "${it.name}:${it.length()}:${it.lastModified()}" }

        internal fun cachedCapability(cache: File, key: String, probe: () -> Boolean): Boolean {
            when (runCatching { cache.readText() }.getOrNull()) {
                "true\n$key" -> return true
                "false\n$key" -> return false
            }
            return probe().also { available ->
                // An interrupted or unwritable cache only causes a fresh probe next time.
                runCatching { cache.writeText("$available\n$key") }
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
