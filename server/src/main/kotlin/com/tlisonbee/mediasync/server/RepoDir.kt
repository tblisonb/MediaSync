package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.library.TagPolicy
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Everything the server keeps lives in one folder, chosen at setup. */
class RepoDir(val root: Path) {
    val db: Path = root.resolve("repo.db")
    val blobs: Path = root.resolve("blobs")
    val uploads: Path = root.resolve("uploads")
    val snapshots: Path = root.resolve("snapshots")

    /** Small JPEGs of the photos, for the web UI; made when first shown, and disposable. */
    val thumbs: Path = root.resolve("thumbs")
    private val settingsFile: Path = root.resolve("settings.json")
    private val secretsFile: Path = root.resolve("admin.json")

    val isSetUp get() = Files.exists(secretsFile) && Files.exists(db)

    fun blob(hash: String): Path = blobs.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash)

    fun thumb(hash: String): Path = thumbs.resolve(hash.substring(0, 2)).resolve("$hash.jpg")

    fun readSettings(): Settings =
        if (Files.exists(settingsFile)) ApiJson.decodeFromString(Files.readString(settingsFile)) else Settings()

    fun writeSettings(settings: Settings) = writePrivate(settingsFile, prettyJson.encodeToString(Settings.serializer(), settings))

    fun readSecrets(): AdminSecrets = ApiJson.decodeFromString(Files.readString(secretsFile))

    fun writeSecrets(secrets: AdminSecrets) = writePrivate(secretsFile, ApiJson.encodeToString(AdminSecrets.serializer(), secrets))

    fun createFolders() {
        for (dir in listOf(root, blobs, uploads, snapshots)) {
            Files.createDirectories(dir)
            setOwnerOnly(dir, "rwx------")
        }
    }

    private fun writePrivate(file: Path, text: String) {
        val tmp = file.resolveSibling(".${file.fileName}.tmp")
        Files.writeString(tmp, text)
        setOwnerOnly(tmp, "rw-------")
        Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private fun setOwnerOnly(path: Path, perms: String) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms)) }
    }

    private companion object {
        val prettyJson = kotlinx.serialization.json.Json(ApiJson) { prettyPrint = true }
    }
}

/** Server settings, editable in the web UI. */
@Serializable
data class Settings(
    val tagPolicy: TagPolicy = TagPolicy(),
    /** Devices delete the clutter they never sync (Cleanup > Leftover files on devices). */
    val deleteClutter: Boolean = false,
)

@Serializable
data class AdminSecrets(
    val passwordHash: String,
    /** Signs browser sessions; replaced when the password changes, which logs every browser out. */
    val sessionKey: String,
)

object Passwords {
    private const val ITERATIONS = 310_000
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256$$ITERATIONS$${b64(salt)}$${b64(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val expected = Base64.getDecoder().decode(parts[3])
        val actual = derive(password, Base64.getDecoder().decode(parts[2]), parts[1].toInt())
        return java.security.MessageDigest.isEqual(expected, actual)
    }

    fun newSessionKey(): String = b64(ByteArray(32).also(random::nextBytes))

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
