package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.MediaDto
import com.tlisonbee.mediasync.api.UploadRequest
import com.tlisonbee.mediasync.api.UploadResponse
import com.tlisonbee.mediasync.media.MediaHasher
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

/**
 * Content-addressed, write-once storage: blobs/ab/cd/<sha256>. Files arrive through resumable
 * uploads in uploads/, and only move into blobs/ once their hash checks out.
 */
class BlobStore(private val repo: Repository) {
    private val dir get() = repo.dir
    private val locks = ConcurrentHashMap<String, Any>()

    fun startUpload(deviceId: String, request: UploadRequest): UploadResponse {
        requireHash(request.hash)
        if (request.size < 0) badRequest("size must be >= 0")
        if (request.name.isBlank() || request.name.length > 255) badRequest("name is required")
        repo.blob(request.hash)?.let { return UploadResponse(null, it.size, done = true, media = mediaOfBlob(it.hash)) }
        return repo.db.tx {
            val existing = repo.db.one(
                "SELECT id FROM upload WHERE device_id = ? AND hash = ? AND size = ?",
                deviceId, request.hash, request.size,
            ) { it.getString(1) }
            val id = existing ?: UUID.randomUUID().toString().also {
                repo.db.exec(
                    "INSERT INTO upload (id, device_id, hash, size, name, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    it, deviceId, request.hash, request.size, request.name, repo.clock(),
                )
            }
            UploadResponse(id, received(id), done = false)
        }.let { if (request.size == 0L) append(deviceId, it.id!!, 0, InputStream.nullInputStream()) else it }
    }

    fun offset(deviceId: String, id: String): Long {
        upload(deviceId, id)
        return received(id)
    }

    /** Appends a chunk at [offset]; finishes the upload once all bytes are in. */
    fun append(deviceId: String, id: String, offset: Long, body: InputStream): UploadResponse =
        synchronized(locks.computeIfAbsent(id) { Any() }) {
            val upload = upload(deviceId, id)
            val current = received(id)
            if (offset != current) throw RequestException("Upload-Offset is $offset but the server has $current bytes", 409)
            val file = partial(id)
            Files.newOutputStream(file, CREATE, WRITE, APPEND).use { out ->
                val remaining = upload.size - current
                val copied = copyAtMost(body, out, remaining + 1)
                if (copied > remaining) {
                    Files.newByteChannel(file, WRITE).use { it.truncate(current) }
                    badRequest("chunk runs past the declared size")
                }
            }
            val now = received(id)
            if (now < upload.size) return UploadResponse(id, now, done = false)
            finish(upload)
        }

    /**
     * Stores a small file sent from the web UI (a subtitle, a poster) in one go; returns its media.
     * Call outside a database transaction.
     */
    fun putBytes(bytes: ByteArray, name: String): MediaRow {
        val tmp = Files.createTempFile(dir.uploads, "web-", ".part")
        try {
            Files.write(tmp, bytes)
            val hash = sha256(tmp)
            val media = MediaHasher.hash(tmp, name)
            val target = dir.blob(hash)
            Files.createDirectories(target.parent)
            if (!Files.exists(target)) Files.move(tmp, target, ATOMIC_MOVE)
            val format = formatOf(name)
            repo.db.tx {
                repo.db.exec("INSERT OR IGNORE INTO blob (hash, size, mime, created_at) VALUES (?, ?, ?, ?)", hash, bytes.size.toLong(), mimeOf(format), repo.clock())
                repo.db.exec("INSERT OR IGNORE INTO media (media_hash, base_blob, format) VALUES (?, ?, ?)", media.hex, hash, format)
            }
            return repo.media(media.hex)!!
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Moves a complete upload into blobs/ once its SHA-256 matches. */
    private fun finish(upload: Upload): UploadResponse {
        val file = partial(upload.id)
        val actual = sha256(file)
        if (actual != upload.hash) {
            Files.deleteIfExists(file)
            repo.db.tx { repo.db.exec("DELETE FROM upload WHERE id = ?", upload.id) }
            throw RequestException("hash mismatch: the file's SHA-256 is $actual; start the upload again", 422)
        }
        val media = MediaHasher.hash(file, upload.name)
        val target = dir.blob(upload.hash)
        Files.createDirectories(target.parent)
        Files.move(file, target, ATOMIC_MOVE)
        val format = formatOf(upload.name)
        repo.db.tx {
            repo.db.exec(
                "INSERT OR IGNORE INTO blob (hash, size, mime, created_at) VALUES (?, ?, ?, ?)",
                upload.hash, upload.size, mimeOf(format), repo.clock(),
            )
            // The first blob seen with a media payload becomes its base copy.
            repo.db.exec(
                "INSERT OR IGNORE INTO media (media_hash, base_blob, format) VALUES (?, ?, ?)",
                media.hex, upload.hash, format,
            )
            repo.db.exec("DELETE FROM upload WHERE id = ?", upload.id)
        }
        locks.remove(upload.id)
        return UploadResponse(null, upload.size, done = true, media = repo.media(media.hex)?.toDto())
    }

    private fun mediaOfBlob(hash: String): MediaDto? =
        repo.db.one("SELECT media_hash FROM media WHERE base_blob = ? LIMIT 1", hash) { it.getString(1) }
            ?.let { repo.media(it)?.toDto() }

    /** Drops partial uploads nobody has touched for a week. */
    fun cleanStaleUploads() {
        val cutoff = repo.clock() - 7.days.inWholeMilliseconds
        val stale = repo.db.tx {
            repo.db.query("SELECT id FROM upload WHERE created_at < ?", cutoff) { it.getString(1) }
                .onEach { repo.db.exec("DELETE FROM upload WHERE id = ?", it) }
        }
        stale.forEach { Files.deleteIfExists(partial(it)) }
    }

    private data class Upload(val id: String, val size: Long, val hash: String, val name: String)

    private fun upload(deviceId: String, id: String): Upload =
        repo.db.one("SELECT * FROM upload WHERE id = ? AND device_id = ?", id, deviceId) {
            Upload(it.getString("id"), it.getLong("size"), it.getString("hash"), it.getString("name"))
        } ?: notFound("no such upload")

    private fun partial(id: String) = dir.uploads.resolve(id)

    private fun received(id: String): Long = partial(id).let { if (Files.exists(it)) Files.size(it) else 0 }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")

        fun requireHash(hash: String) {
            if (!HASH.matches(hash)) badRequest("not a lowercase hex SHA-256: $hash")
        }

        fun isHash(hash: String) = HASH.matches(hash)

        /** The extension a rendition is stored with, from the uploaded file's name. */
        fun formatOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "jpeg" -> "jpg"
                ext.matches(Regex("[a-z0-9]{1,8}")) -> ext
                else -> "bin"
            }
        }

        fun mimeOf(format: String): String = when (format) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aif", "aiff" -> "audio/aiff"
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> "application/octet-stream"
        }

        fun sha256(file: java.nio.file.Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun copyAtMost(input: InputStream, out: java.io.OutputStream, limit: Long): Long {
            val buffer = ByteArray(1 shl 16)
            var total = 0L
            while (total < limit) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return total
        }
    }
}

fun MediaRow.toDto() = MediaDto(mediaHash, baseBlob, format, size, durationMs)
