package dev.renakoni.voicebox.model.qwen3

import android.content.Context
import android.system.Os
import android.util.Log
import dev.renakoni.voicebox.core.DeviceFacts
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object QwenDevices {
    @JvmStatic private external fun probe(htp: Boolean): Boolean

    fun acceleratedLibrary(directory: File) = File(directory, "accelerated/libqwen3_jni.so")

    fun available(context: Context, directory: File): Int {
        if (!DeviceFacts.qwenGpuAvailable) return 0
        return runCatching {
            configure(context, directory, false)
            System.load(acceleratedLibrary(directory).absolutePath)
            if (!probe(false)) return 0
            val arch = DeviceFacts.hexagonArch
            val dsp = File(directory, "accelerated/libggml-htp-v${arch}.so")
            if (arch >= 73 && dsp.isFile && probe(true)) 3 else 1
        }.onFailure { Log.w("QwenWorker", "Acceleration probe failed", it) }.getOrDefault(0)
    }

    fun configure(context: Context, directory: File, enableHtp: Boolean) {
        Os.setenv("GGML_VK_CACHE_DIR", context.cacheDir.absolutePath, true)
        Os.setenv("GGML_HEXAGON_LIBHTP_DIR", File(directory, "accelerated").absolutePath, true)
        Os.setenv("GGML_HEXAGON_DEVICES", "HTP0", true)
        if (enableHtp) {
            Os.setenv("GGML_ENABLE_HEXAGON", "1", true)
        } else {
            Os.unsetenv("GGML_ENABLE_HEXAGON")
        }
        if (DeviceFacts.hexagonArch != 75) return
        val name = "qwen-vk2-1124406273-2150531084.bin"
        val cache = File(context.cacheDir, name)
        if (!cache.exists()) runCatching {
            val temp = File.createTempFile("qwen-vk", ".tmp", context.cacheDir)
            try {
                File(directory, "assets/$name").copyTo(temp, overwrite = true)
                Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }.onFailure { Log.w("QwenWorker", "Cannot seed pipeline cache", it) }
    }
}
