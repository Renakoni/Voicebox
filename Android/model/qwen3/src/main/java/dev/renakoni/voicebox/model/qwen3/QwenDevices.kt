package dev.renakoni.voicebox.model.qwen3

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object QwenDevices {
    @JvmStatic private external fun probe(path: String, v75: Boolean): Int

    fun acceleratedLibrary(directory: File) = File(directory, "accelerated/libqwen3_jni.so")
    val v75 get() = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("SM8650", ignoreCase = true)

    fun available(context: Context, directory: File): Int {
        if (Build.VERSION.SDK_INT < 31) return 0
        return runCatching {
            configure(context, directory)
            System.load(File(directory, "libqwen3_probe.so").absolutePath)
            probe(acceleratedLibrary(directory).absolutePath, v75)
        }.onFailure { Log.w("QwenWorker", "Acceleration probe failed", it) }.getOrDefault(0)
    }

    fun configure(context: Context, directory: File) {
        Os.setenv("GGML_VK_CACHE_DIR", context.cacheDir.absolutePath, true)
        if (!v75) return
        Os.setenv("GGML_HEXAGON_LIBHTP_DIR", File(directory, "accelerated").absolutePath, true)
        Os.setenv("GGML_HEXAGON_DEVICES", "HTP0", true)
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
