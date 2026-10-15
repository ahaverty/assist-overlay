package com.ahaverty.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A photo going along with the next question. It is shrunk on the phone (Friday and an advert need no more than
 * [MAX_EDGE] px), kept in app storage for the chat history, and uploaded to Home Assistant's image store as soon as
 * it is picked: the friday-voice bridge fetches it from there by [id] (HA's pipeline itself only carries text).
 */
data class Photo(val file: File, val thumb: Bitmap, val id: String? = null, val failed: Boolean = false)

object Photos {
    const val MAX_EDGE = 1600
    const val MAX_PER_QUESTION = 10
    private const val KEEP_DAYS = History.DAYS

    fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    /** Where the camera writes a new picture (shared with the camera app through the FileProvider). */
    fun cameraFile(context: Context) = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot.jpg") }

    /** Decode [uri] upright (ImageDecoder applies the EXIF rotation), shrink it and save it as a JPEG. */
    suspend fun prepare(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = info.size.width to info.size.height
            val scale = MAX_EDGE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
        val out = File(dir(context), "${System.currentTimeMillis().toString(36)}${(100..999).random()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        out
    }

    /** A small copy for the text bar and the chat. */
    fun thumb(file: File, edge: Int = 192): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Photos of chats that have dropped out of the history. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
