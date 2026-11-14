package dev.rinstel.genie_tts.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Small, app-owned thumbnails; the gallery/camera original is never retained here. */
class VoiceAvatarStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("voice_avatars", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "studio_avatars").apply { mkdirs() }

    fun path(id: String): String = preferences.getString(id, "").orEmpty()

    fun save(id: String, path: String) {
        val previous = path(id)
        preferences.edit().apply { if (path.isBlank()) remove(id) else putString(id, path) }.apply()
        discard(previous)
    }

    /** A template may share its saved avatar with a clone until either one changes it. */
    fun discard(path: String) {
        if (path.isBlank() || preferences.all.values.contains(path)) return
        File(path).takeIf { it.parentFile == root }?.delete()
    }

    fun import(uri: Uri): String {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        val target = File(root, "${UUID.randomUUID()}.jpg")
        try {
            val avatar = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            try {
                // ImageDecoder applies the source orientation before this centered crop.
                val side = minOf(decoded.width, decoded.height)
                val left = (decoded.width - side) / 2
                val top = (decoded.height - side) / 2
                Canvas(avatar).apply {
                    drawColor(Color.rgb(244, 241, 237))
                    drawBitmap(decoded, Rect(left, top, left + side, top + side), Rect(0, 0, 384, 384),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                target.outputStream().use { check(avatar.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            } finally { avatar.recycle() }
            return target.absolutePath
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally { decoded.recycle() }
    }
}
