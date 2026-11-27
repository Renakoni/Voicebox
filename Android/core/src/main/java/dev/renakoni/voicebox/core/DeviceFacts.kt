package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Platform facts for the running process, including APKs running under ABI translation. */
data class DeviceFacts(val manufacturer: String, val soc: String, val hardware: String, val model: String,
    val fingerprint: String, val abi: String) {
    data class GpuRecheck(val changed: Boolean, val conclusive: Boolean)
    enum class NpuSupport { AVAILABLE, UNAVAILABLE, UNKNOWN;
        companion object {
            internal fun fromProbe(hexagon: Int, accelerator: Int) = when {
                hexagon > 0 || accelerator > 0 -> AVAILABLE
                hexagon == 0 && accelerator == 0 -> UNAVAILABLE
                else -> UNKNOWN
            }
        }
    }

    companion object {
        @JvmStatic private external fun queryHexagonArch(): Int
        @JvmStatic private external fun queryAndroidAccelerator(): Int
        @JvmStatic private external fun queryVulkanCompute(cacheable: BooleanArray): Int
        private data class Vulkan(val level: Int = 0, val conclusive: Boolean = false)
        // A negative value means that the driver query was inconclusive.
        private data class Npu(val hexagon: Int = -1, val accelerator: Int = -1)
        private lateinit var gpuCache: Lazy<ProbeCache>
        @Volatile private lateinit var vulkan: Lazy<Vulkan>
        private lateinit var npu: Lazy<Npu>
        internal var workerHexagonArch: Int? = null
        internal var workerVulkanLevel: Int? = null

        fun initialize(context: Context) {
            gpuCache = lazy { ProbeCache(context, "device-gpu") }
            vulkan = lazy {
                val saved = gpuCache.value.read("") { Vulkan(it.getInt("level"), it.getBoolean("conclusive")) } ?: Vulkan()
                if (saved.conclusive) saved else measureVulkan(saved)
            }
            npu = lazy {
                val cache = ProbeCache(context, "device-npu")
                val saved = cache.read("") { Npu(it.getInt("hexagon"), it.getInt("accelerator")) } ?: Npu()
                if (saved.hexagon >= 0 && saved.accelerator >= 0) return@lazy saved
                try {
                    System.loadLibrary("voicebox_device_npu")
                    val hexagon = if (saved.hexagon >= 0) saved.hexagon else queryHexagonArch()
                    val accelerator = when {
                        hexagon > 0 -> 1
                        saved.accelerator >= 0 -> saved.accelerator
                        else -> queryAndroidAccelerator()
                    }
                    Npu(hexagon, accelerator).also {
                        if (it != saved) cache.write("", JSONObject().put("hexagon", hexagon).put("accelerator", accelerator))
                    }
                } catch (error: LinkageError) {
                    android.util.Log.w("VoiceBoxProbe", "Could not load device NPU probes", error)
                    saved
                }
            }
        }

        private fun measureVulkan(previous: Vulkan): Vulkan {
            val cacheable = BooleanArray(1)
            val measured = try {
                System.loadLibrary("voicebox_device_gpu")
                queryVulkanCompute(cacheable)
            } catch (error: LinkageError) {
                android.util.Log.w("VoiceBoxProbe", "Could not load device GPU probe", error)
                0
            }
            val result = Vulkan(if (cacheable[0]) measured else maxOf(previous.level, measured), cacheable[0])
            if (result != previous) {
                gpuCache.value.write("", JSONObject().put("level", result.level).put("conclusive", result.conclusive))
            }
            return result
        }

        // Called on the service worker. Readers keep the previous result while the driver is busy.
        fun recheckVulkan(): GpuRecheck {
            val previous = vulkan.value
            val result = measureVulkan(previous)
            vulkan = lazyOf(result)
            return GpuRecheck(result.level != previous.level, result.conclusive)
        }

        fun preload() {
            vulkan.value
            npu.value
        }

        val hexagonArch get() = workerHexagonArch ?: npu.value.hexagon
        val npuSupport get() = npu.value.let { NpuSupport.fromProbe(it.hexagon, it.accelerator) }
        val hasDeviceAccelerator get() = npuSupport == NpuSupport.AVAILABLE
        internal val vulkanLevel get() = workerVulkanLevel ?: vulkan.value.level
        val hasVulkanCompute get() = vulkanLevel > 0
        val hasVulkan12Storage16 get() = vulkanLevel == 2

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
