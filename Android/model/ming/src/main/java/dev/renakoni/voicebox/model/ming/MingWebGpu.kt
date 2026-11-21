package dev.renakoni.voicebox.model.ming

import android.util.Log
import java.io.File

internal object MingWebGpu {
    @JvmStatic private external fun probe(graph: String): Boolean

    fun available(directory: File): Boolean = runCatching {
        System.load(File(directory, "libonnxruntime.so").absolutePath)
        System.load(File(directory, "libming-gpu-probe.so").absolutePath)
        probe(File(directory, "provider-probe.onnx").absolutePath)
    }.onFailure { Log.w("MingWorker", "WebGPU probe failed", it) }.getOrDefault(false)
}
