package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Platform facts for the running process, including APKs running under ABI translation. */
data class DeviceFacts(val manufacturer: String, val soc: String, val hardware: String, val model: String,
    val fingerprint: String, val abi: String) {
    companion object {
        @JvmStatic private external fun queryHexagonArch(): Int
        @JvmStatic private external fun hasAndroidAccelerator(): Boolean
        @JvmStatic private external fun queryVulkanCompute(cacheable: BooleanArray): Int
        private data class Capabilities(val vulkan: Int, val hexagon: Int, val accelerator: Boolean)
        private lateinit var capabilities: Lazy<Capabilities>
        internal var workerHexagonArch: Int? = null

        fun initialize(context: Context) {
            capabilities = lazy {
                val cache = ProbeCache(context, "device")
                cache.read("") { Capabilities(it.getInt("vulkan"), it.getInt("hexagon"), it.getBoolean("accelerator")) }
                    ?: try {
                        System.loadLibrary("voicebox_device_npu")
                        val cacheable = BooleanArray(1)
                        val vulkan = queryVulkanCompute(cacheable)
                        val hexagon = queryHexagonArch()
                        val accelerator = hexagon > 0 || hasAndroidAccelerator()
                        if (cacheable[0]) {
                            cache.write("", JSONObject().put("vulkan", vulkan).put("hexagon", hexagon)
                                .put("accelerator", accelerator))
                        }
                        Capabilities(vulkan, hexagon, accelerator)
                    } catch (error: LinkageError) {
                        android.util.Log.w("VoiceBoxProbe", "Could not load device probes", error)
                        Capabilities(0, 0, false)
                    }
            }
        }

        val hexagonArch get() = workerHexagonArch ?: capabilities.value.hexagon
        val hasDeviceAccelerator get() = capabilities.value.accelerator
        val hasVulkanCompute get() = capabilities.value.vulkan != 0
        val hasVulkan12Storage16 get() = capabilities.value.vulkan == 2

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
