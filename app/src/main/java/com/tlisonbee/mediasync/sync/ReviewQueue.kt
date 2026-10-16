package com.tlisonbee.mediasync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.app
import java.io.File

/**
 * Decisions made in the app or on a notification, waiting for the worker to apply them. Several
 * taps before it runs add up; the worker takes them all at once.
 */
object ReviewQueue {
    private fun pending(context: Context) = File(context.filesDir, "decisions.json")

    /** What the last "changes waiting" notification offered, for its Accept all button. */
    private fun notified(context: Context) = File(context.filesDir, "notified-decisions.json")

    @Synchronized
    fun add(context: Context, decisions: Decisions) {
        val merged = read(pending(context))?.let {
            Decisions(it.acceptIncoming + decisions.acceptIncoming, it.acceptOutgoing + decisions.acceptOutgoing, it.undo + decisions.undo, it.revert + decisions.revert)
        } ?: decisions
        write(pending(context), merged)
    }

    @Synchronized
    fun take(context: Context): Decisions? = read(pending(context)).also { pending(context).delete() }

    @Synchronized
    fun rememberNotified(context: Context, decisions: Decisions) = write(notified(context), decisions)

    /** Assets whose arrival or removal was already approved by picking them on this phone. */
    private fun picked(context: Context) = File(context.filesDir, "picked.json")

    @Synchronized
    fun approvePicked(context: Context, assetIds: Collection<String>) {
        val before = read(picked(context))?.acceptIncoming.orEmpty()
        write(picked(context), Decisions(acceptIncoming = before + assetIds))
    }

    /** The picked assets among [waiting]; they're forgotten once taken. */
    @Synchronized
    fun takePicked(context: Context, waiting: Set<String>): Set<String> {
        val approved = read(picked(context))?.acceptIncoming.orEmpty()
        val now = approved intersect waiting
        val rest = approved - now
        if (rest.isEmpty()) picked(context).delete() else write(picked(context), Decisions(acceptIncoming = rest))
        return now
    }

    @Synchronized
    fun acceptNotified(context: Context): Boolean {
        val decisions = read(notified(context)) ?: return false
        notified(context).delete()
        add(context, decisions)
        return true
    }

    private fun read(file: File): Decisions? =
        runCatching { ApiJson.decodeFromString(Decisions.serializer(), file.readText()) }.getOrNull()

    private fun write(file: File, decisions: Decisions) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiJson.encodeToString(Decisions.serializer(), decisions))
        tmp.renameTo(file)
    }
}

/** The "Accept all" button on the changes-waiting notification. */
class AcceptAllReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.app.settings.ready) return
        if (ReviewQueue.acceptNotified(context)) SyncScheduler.apply(context)
        context.getSystemService(android.app.NotificationManager::class.java).cancel(SyncWorker.ID_REVIEW)
    }
}
