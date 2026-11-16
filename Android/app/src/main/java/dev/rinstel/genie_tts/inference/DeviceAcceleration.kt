package dev.rinstel.genie_tts.inference

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DeviceAccelerationStatus(
    val device: String = "正在读取设备信息…",
    val available: Boolean = false,
    val detail: String = "正在验证 NPU",
    val checking: Boolean = true,
)

/** Owned by the service; detection and model work run on the same worker. */
class DeviceAcceleration(private val context: Context) {
    private val mutableStatus = MutableStateFlow(DeviceAccelerationStatus())
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
            Log.i("VoiceboxAcceleration", reason)
            runtimeDirectory = null
            mutableStatus.value = DeviceAccelerationStatus(device, detail = "NPU 验证未通过", checking = false)
        }
        if (!qualcomm || "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            unavailable("未检测到兼容的高通 NPU")
            return
        }
        if (!webGpu) { unavailable("当前运行库不支持混合加速"); return }
        val runtime = File(context.filesDir, "QualcommRuntime")
        if (!hasRuntimeFiles(runtime)) { unavailable("未安装 NPU 加速组件"); return }
        mutableStatus.value = DeviceAccelerationStatus(device)
        val probe = File(context.cacheDir, "qnn-capability.onnx")
        try {
            context.assets.open("device-acceleration/probe.onnx").use { input -> probe.outputStream().use(input::copyTo) }
            QnnSession.probe(runtime, probe)
            runtimeDirectory = runtime
            mutableStatus.value = DeviceAccelerationStatus(device, true, "NPU 验证通过", checking = false)
        } catch (error: Exception) {
            Log.w("VoiceboxAcceleration", "NPU capability probe failed", error)
            unavailable("NPU 验证未通过")
        } catch (error: LinkageError) {
            Log.w("VoiceboxAcceleration", "NPU runtime could not load", error)
            unavailable("NPU 加速组件不兼容")
        } finally { probe.delete() }
    }

    fun modelUnavailable() {
        runtimeDirectory = null
        mutableStatus.value = mutableStatus.value.copy(available = false, detail = "NPU 验证未通过")
    }

    companion object {
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
