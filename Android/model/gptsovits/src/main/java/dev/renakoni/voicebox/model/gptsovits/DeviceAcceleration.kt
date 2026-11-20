package dev.renakoni.voicebox.model.gptsovits

import dev.renakoni.voicebox.core.i18n.UiText

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DeviceAccelerationStatus(
    val device: String = "",
    val available: Boolean = false,
    val detail: UiText = UiText("npu_checking"),
    val checking: Boolean = true,
)

/** Owned by the service; detection and model work run on the same worker. */
class DeviceAcceleration(private val context: Context) {
    private val mutableStatus = MutableStateFlow(DeviceAccelerationStatus())
    private val capabilityCache = File(context.noBackupFilesDir, "npu-capability")
    val status = mutableStatus.asStateFlow()
    @Volatile var runtimeDirectory: File? = null
        private set

    fun detect(webGpu: Boolean) {
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.BOARD
        val qualcomm = isQualcomm(manufacturer, Build.HARDWARE)
        val device = listOf(if (qualcomm) "Qualcomm" else manufacturer, soc)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" · ")
            .ifBlank { Build.MODEL }
        fun unavailable(reason: String) {
            Log.i("VoiceBoxAcceleration", reason)
            runtimeDirectory = null
            mutableStatus.value = DeviceAccelerationStatus(device, detail = UiText("npu_unavailable"), checking = false)
        }
        if (!qualcomm || "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            unavailable("未检测到兼容的高通 NPU")
            return
        }
        if (!webGpu) { unavailable("当前运行库不支持混合加速"); return }
        val runtime = File(context.filesDir, "QualcommRuntime")
        if (!hasRuntimeFiles(runtime)) { unavailable("未安装 NPU 加速组件"); return }
        mutableStatus.value = DeviceAccelerationStatus(device)
        val updatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val key = capabilityCacheKey(Build.FINGERPRINT, updatedAt, runtime)
        val available = cachedCapability(capabilityCache, key) {
            val probe = File(context.cacheDir, "qnn-capability.onnx")
            try {
                Log.i("VoiceBoxAcceleration", "验证 NPU 能力")
                context.assets.open("device-acceleration/probe.onnx").use { input -> probe.outputStream().use(input::copyTo) }
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
            mutableStatus.value = DeviceAccelerationStatus(device, true, UiText("npu_available"), checking = false)
        } else unavailable("NPU capability check failed")
    }

    fun modelUnavailable() {
        capabilityCache.delete()
        runtimeDirectory = null
        mutableStatus.value = mutableStatus.value.copy(available = false, detail = UiText("npu_unavailable"))
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
