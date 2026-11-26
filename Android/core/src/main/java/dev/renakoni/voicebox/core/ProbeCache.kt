package dev.renakoni.voicebox.core

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Host-owned probe results, kept off backups and scoped to the system image and process ABI. */
internal class ProbeCache(context: Context, name: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "probe-v1-$name.json"))
    private val system = "${Build.FINGERPRINT}/${DeviceFacts.read(context).abi}"
    private var record = try {
        file.openRead().bufferedReader().use { JSONObject(it.readText()) }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w("VoiceBoxProbe", "Could not read $name probe result", error)
        null
    }

    fun <T> read(key: String, decode: (JSONObject) -> T): T? = record
        ?.takeIf { it.optString("system") == system && it.optString("key") == key }
        ?.optJSONObject("result")?.let { runCatching { decode(it) }.getOrNull() }

    fun write(key: String, result: JSONObject) {
        val entry = JSONObject().put("system", system).put("key", key).put("result", result)
        record = entry
        try {
            val output = file.startWrite()
            try {
                output.write(entry.toString().toByteArray())
                file.finishWrite(output)
            } catch (error: IOException) {
                file.failWrite(output)
                throw error
            }
        } catch (error: IOException) {
            Log.w("VoiceBoxProbe", "Could not persist probe result", error)
        }
    }
}
