package com.tlisonbee.mediasync.player.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * Album covers, copied out of the files once at [SIZE] pixels into the app's own storage, keyed by
 * a hash of the picture so an album's tracks share one. Lists and the notification read these
 * copies, never the audio files.
 */
class Covers(private val dir: File) {
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        dir.mkdirs()
    }

    fun file(key: String) = File(dir, "$key.jpg")

    /** Gets a cover that isn't stored yet (one on the server) as image bytes; null when it can't. */
    @Volatile
    var fetch: ((String) -> ByteArray?)? = null

    /** Covers [fetch] couldn't get, not asked for again until the app restarts. */
    private val unavailable = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Stores a cover from a file's embedded picture; returns its key, or null if it can't be decoded. */
    fun fromBytes(data: ByteArray): String? {
        val key = hash(data)
        return if (file(key).isFile || save(key) { decode(data) }) key else null
    }

    /** Stores a cover from an image beside the tracks (cover.jpg and the like). */
    fun fromImage(image: File): String? {
        val key = "f" + hash("${image.path}\u0000${image.length()}\u0000${image.lastModified()}".toByteArray())
        return if (file(key).isFile || save(key) { decodeFile(image) }) key else null
    }

    private fun save(key: String, decode: () -> Bitmap?): Boolean {
        val bitmap = runCatching(decode).getOrNull() ?: return false
        val scaled = scale(bitmap)
        val tmp = File(dir, ".$key.tmp")
        val ok = tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return ok && tmp.renameTo(file(key))
    }

    private fun decode(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample(bounds, SIZE) })
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample(bounds, SIZE) })
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= SIZE) return bitmap
        val f = SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
    }

    /** A stored cover at about [sizePx] (at least), from memory when it can. Call off the main thread. */
    fun load(key: String, sizePx: Int): Bitmap? {
        val bucket = if (sizePx <= SIZE / 4) SIZE / 4 else if (sizePx <= SIZE / 2) SIZE / 2 else SIZE
        val memoryKey = "$key@$bucket"
        memory.get(memoryKey)?.let { return it }
        val f = file(key)
        if (!f.isFile) {
            if (key in unavailable) return null
            val bytes = fetch?.invoke(key)
            if (bytes == null || !save(key) { decode(bytes) }) {
                unavailable += key
                return null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        val bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample(bounds, bucket) }) ?: return null
        memory.put(memoryKey, bitmap)
        return bitmap
    }

    /** Deletes the stored covers no track uses any more. */
    fun keepOnly(keys: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".jpg") && f.name.removeSuffix(".jpg") !in keys) f.delete()
        }
    }

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** The longest side of a stored cover, enough for the Now Playing screen. */
        const val SIZE = 720

        /** The image files beside tracks that count as their cover, best first. */
        val FOLDER_IMAGES = listOf("cover", "folder", "front", "albumart", "album")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        private fun sample(bounds: BitmapFactory.Options, target: Int): Int {
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
            return sample
        }

        private fun hash(data: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(data).take(12).joinToString("") { "%02x".format(it) }
    }
}
