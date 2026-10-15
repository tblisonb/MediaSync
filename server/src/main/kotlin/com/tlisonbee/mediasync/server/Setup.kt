package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.TagField
import java.util.UUID

/** `mediasync-server setup`: the repository folder, an admin password and the tag policy. */
object Setup {
    const val MIN_PASSWORD = 10

    /** Creates a repository in [dir]; used by the interactive setup and by tests. */
    fun create(dir: RepoDir, password: String, settings: Settings = Settings()) {
        require(!dir.isSetUp) { "${dir.root} already holds a repository" }
        require(password.length >= MIN_PASSWORD) { "The admin password needs at least $MIN_PASSWORD characters" }
        dir.createFolders()
        dir.writeSettings(settings)
        Repository(dir).use { repo ->
            repo.db.tx { repo.insertDevice(UUID.randomUUID().toString(), DeviceKind.WEB, "Web UI", tokenHash = null) }
        }
        // Written last: its presence is what marks the repository as set up.
        dir.writeSecrets(AdminSecrets(Passwords.hash(password), Passwords.newSessionKey()))
    }

    /** A new password and session key, which logs every browser out. Takes effect on restart. */
    fun changePassword(dir: RepoDir, password: String) {
        require(password.length >= MIN_PASSWORD) { "The admin password needs at least $MIN_PASSWORD characters" }
        dir.writeSecrets(AdminSecrets(Passwords.hash(password), Passwords.newSessionKey()))
    }

    fun interactive(dir: RepoDir): Int {
        if (dir.isSetUp) {
            println("${dir.root} already holds a repository.")
            if (!ask("Change the admin password? [y/N] ").trim().lowercase().startsWith("y")) return 0
            changePassword(dir, newPassword())
            println("Password changed. Restart the server for it to take effect: sudo systemctl restart mediasync")
            return 0
        }

        println("Setting up a MediaSync repository in ${dir.root}")
        val password = newPassword()
        println()
        println("Tag policy for music: only these fields are kept; every other tag is removed on import.")
        println("  " + TagField.MUSIC.joinToString(", ") + ", plus the front cover")
        val fields = if (ask("Keep this list? [Y/n] ").trim().lowercase().startsWith("n")) {
            val known = TagField.MUSIC.toSet()
            val chosen = ask("Fields to keep, separated by commas: ").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val unknown = chosen.filter { it !in known }
            require(unknown.isEmpty()) { "Unknown fields: ${unknown.joinToString()}. Choose from: ${known.joinToString()}" }
            chosen
        } else {
            TagField.MUSIC
        }
        val cover = !ask("Keep front covers? [Y/n] ").trim().lowercase().startsWith("n")
        val policy = TagPolicy(
            fields = mapOf(AssetKind.AUDIO to fields),
            pictures = mapOf(AssetKind.AUDIO to if (cover) listOf(TagPolicy.FRONT_COVER) else emptyList()),
        )
        create(dir, password, Settings(policy))
        println()
        println("Repository created in ${dir.root}. The tag policy can be changed later in the web UI's Settings.")
        return 0
    }

    private fun newPassword(): String {
        while (true) {
            val first = secret("Admin password for the web UI (at least $MIN_PASSWORD characters): ")
            if (first.length < MIN_PASSWORD) {
                println("Too short.")
                continue
            }
            if (secret("Again: ") == first) return first
            println("Those didn't match.")
        }
    }

    private fun secret(prompt: String): String =
        System.console()?.readPassword(prompt)?.let(::String) ?: ask(prompt)

    private fun ask(prompt: String): String {
        print(prompt)
        System.out.flush()
        return readlnOrNull() ?: throw IllegalArgumentException("Setup needs answers on standard input")
    }
}
