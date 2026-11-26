package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Build
import java.io.File

/** Platform facts for the running process, including APKs running under ABI translation. */
data class DeviceFacts(val manufacturer: String, val soc: String, val hardware: String, val model: String,
    val fingerprint: String, val abi: String) {
    companion object {
        @JvmStatic private external fun queryHexagonArch(): Int
        @JvmStatic private external fun hasAndroidAccelerator(): Boolean
        @JvmStatic private external fun queryVulkanCompute(): Int
        private val probeLoaded by lazy {
            try {
                System.loadLibrary("voicebox_device_npu")
                true
            } catch (_: UnsatisfiedLinkError) { false }
        }
        val hexagonArch by lazy { if (probeLoaded) queryHexagonArch() else 0 }
        val hasDeviceAccelerator by lazy { probeLoaded && (hasAndroidAccelerator() || hexagonArch > 0) }
        private val vulkanCompute by lazy { if (probeLoaded) queryVulkanCompute() else 0 }
        val hasVulkanCompute get() = vulkanCompute != 0
        val hasVulkan12Storage16 get() = vulkanCompute == 2

        fun read(context: Context): DeviceFacts {
            val abi = when (File(context.applicationInfo.nativeLibraryDir).name) {
                "arm64" -> "arm64-v8a"
                "x86_64" -> "x86_64"
                else -> Build.SUPPORTED_ABIS.first()
            }
            return DeviceFacts(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE,
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.BOARD,
                Build.HARDWARE, Build.MODEL, Build.FINGERPRINT, abi)
        }
    }
}
