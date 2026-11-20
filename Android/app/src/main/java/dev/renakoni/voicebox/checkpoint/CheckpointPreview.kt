package dev.renakoni.voicebox.checkpoint

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.i18n.UiText
import dev.renakoni.voicebox.i18n.requireUi
import dev.renakoni.voicebox.i18n.errorUi

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Read only the header and, for unmarked community checkpoints, tensor metadata. */
internal object CheckpointPreview {
    fun read(context: Context, uri: Uri): String? {
        val prefix = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(8192)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            bytes.copyOf(size)
        }
            ?: errorUi(UiText(R.string.checkpoint_read_failed))
        identifyHeader(prefix)?.let { return it }
        // Some document providers expose a pipe. In that case defer recognition to
        // import rather than copying an entire checkpoint just to render a label.
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                TorchCheckpoint(File("/proc/self/fd/${descriptor.fd}")).use { checkpoint ->
                    val config = (checkpoint.root["config"] as? Map<*, *>)?.get("model") as? Map<*, *>
                    when {
                        checkpoint.root.containsKey("lora_rank") -> "lora"
                        checkpoint.weights.containsKey("cfm.estimator.d_embed.time_mlp.0.weight") -> "v5dev"
                        checkpoint.weights.containsKey("cfm.estimator.proj_out.weight") ->
                            if (config?.get("version") == "v5turbo") "v5turbo" else "v5"
                        config?.get("gin_channels") == 1024L && config["upsample_initial_channel"] == 512L -> "v2Pro"
                        config?.get("gin_channels") == 1024L && config["upsample_initial_channel"] == 768L -> "v2ProPlus"
                        else -> null
                    }
                }
            }
        }.getOrNull()
    }

    internal fun identifyHeader(prefix: ByteArray): String? {
        requireUi(prefix.size >= 4 && prefix[2] == 3.toByte() && prefix[3] == 4.toByte()) {
            UiText(R.string.checkpoint_complete_required)
        }
        // Upstream process_ckpt.py fingerprints the first 8192 bytes of official weights.
        val hash = MessageDigest.getInstance("MD5").digest(prefix).joinToString("") { "%02x".format(it) }
        val official = mapOf(
            "c7e9fce2223f3db685cdfa1e6368728a" to "v2Pro",
            "66b313e39455b57ab1b0bc0b239c9d0a" to "v2ProPlus",
            "c4ce1f839a0d271e41963f9fb16ade76" to "v5dev",
            "6bc5cb2a195cc8ec22b2e723cd4d6f47" to "v5turbo",
        )
        return official[hash] ?: when (prefix.copyOfRange(0, 2).toString(Charsets.US_ASCII)) {
            "05" -> "v2Pro"
            "06" -> "v2ProPlus"
            "07", "08" -> "lora"
            "PK" -> null
            else -> "unsupported"
        }
    }
}
