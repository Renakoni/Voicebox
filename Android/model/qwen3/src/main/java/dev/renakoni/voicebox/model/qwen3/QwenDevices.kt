package dev.renakoni.voicebox.model.qwen3

import android.content.Context
import android.system.Os
import dev.renakoni.voicebox.core.DeviceFacts
import java.io.File

internal object QwenDevices {
    @JvmStatic private external fun probe(htp: Boolean): Boolean
    @JvmStatic private external fun hasInt8Kernels(): Boolean

    private val kernelDirectory by lazy { if (hasInt8Kernels()) "accelerated/int8mm" else "accelerated" }

    fun acceleratedLibrary(directory: File): File {
        System.load(File(directory, "libqwen3_cpu_features.so").absolutePath)
        return File(directory, "$kernelDirectory/libqwen3_jni.so")
    }

    fun hasGpu(context: Context, directory: File): Boolean {
        configure(context, directory, false)
        System.load(acceleratedLibrary(directory).absolutePath)
        return probe(false)
    }

    fun hasHtp(directory: File): Boolean {
        val arch = DeviceFacts.hexagonArch
        if (arch < 73 || !File(directory, "accelerated/libggml-htp-v${arch}.so").isFile) return false
        configureHtp(directory)
        System.load(acceleratedLibrary(directory).absolutePath)
        return probe(true)
    }

    private fun configureHtp(directory: File) {
        Os.setenv("GGML_HEXAGON_LIBHTP_DIR", File(directory, "accelerated").absolutePath, true)
        Os.setenv("GGML_HEXAGON_DEVICES", "HTP0", true)
    }

    fun configure(context: Context, directory: File, enableHtp: Boolean) {
        Os.setenv("GGML_VK_CACHE_DIR", context.cacheDir.absolutePath, true)
        if (enableHtp) {
            configureHtp(directory)
            Os.setenv("GGML_ENABLE_HEXAGON", "1", true)
        } else {
            Os.unsetenv("GGML_ENABLE_HEXAGON")
        }
    }
}
