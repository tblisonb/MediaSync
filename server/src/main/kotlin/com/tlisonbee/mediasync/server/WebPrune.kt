package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.RevisionDto
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.ul

/** The timeframes History > Prune history offers; [days] null keeps only the current files. */
enum class PruneAge(val key: String, val label: String, val days: Long?) {
    MONTH("30d", "Older than 30 days", 30),
    QUARTER("90d", "Older than 90 days", 90),
    HALF("6m", "Older than 6 months", 182),
    YEAR("1y", "Older than a year", 365),
    ALL("all", "All of it: keep only the current files", null),
    ;

    /** The newest revision this timeframe prunes through. */
    fun through(repo: Repository): Long = days?.let { repo.revBefore(repo.clock() - it * 24 * 3600_000L) } ?: repo.head()

    companion object {
        val DEFAULT = QUARTER
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** The Prune history box on the History page, and the dialog when a timeframe is being reviewed. */
class PruneView(
    val history: Long,
    val prunedThrough: Long,
    val last: PruneRecord?,
    val blocker: String?,
    val dialog: PruneDialog?,
    /** Just pruned: the History page says what went. */
    val justPruned: Boolean,
)

/** What pruning [age] would drop: revisions [PrunePlan.from]–[PrunePlan.through], the newest of them in [shown]. */
class PruneDialog(val age: PruneAge, val through: Long, val plan: PrunePlan?, val count: Int, val shown: List<RevisionDto>, val firstAt: Long?, val lastAt: Long?)

/** How many of the revisions being pruned the dialog lists. */
private const val SHOWN = 12

fun Repository.pruneView(review: String?, justPruned: Boolean): PruneView {
    val prunedThrough = prunedThrough()
    val blocker = pruneBlocker()
    val dialog = PruneAge.of(review)?.let { age ->
        val through = age.through(this)
        val from = prunedThrough + 1
        val count = if (through >= from) db.long("SELECT COUNT(*) FROM revision WHERE rev BETWEEN ? AND ?", from, through).toInt() else 0
        if (count == 0) return@let PruneDialog(age, through, null, 0, emptyList(), null, null)
        val plan = if (blocker == null) prune(through, dryRun = true) else null
        val shown = revisions(through + 1, SHOWN).revisions.filter { it.rev >= from }
        val at = { rev: Long -> db.long("SELECT created_at FROM revision WHERE rev = (SELECT MIN(rev) FROM revision WHERE rev >= ?)", rev) }
        PruneDialog(age, through, plan, count, shown, at(from), at(through))
    }
    return PruneView(historyBytes(), prunedThrough, lastPrune(), blocker, dialog, justPruned)
}

internal fun FlowContent.pruneBox(view: PruneView) = section("card prune") {
    h2 { +"Prune history" }
    if (view.justPruned) view.last?.let { last ->
        p("notice") { +"Pruned history through r${last.through}: dropped ${plural(last.versions, "old version", "old versions")} and freed ${bytes(last.bytes)}." }
    }
    p("muted") {
        +"Older versions take ${bytes(view.history)}: they're what undo and revert bring back. "
        +"Pruning deletes the ones older than a timeframe for good; every revision after it stays revertible."
    }
    view.last?.let { p("muted small") { +"Pruned through r${it.through} ${ago(it.at)}." } }
    view.blocker?.let { p("notice warn") { +it } }
    form(action = "/revisions", method = FormMethod.get, classes = "prune-pick") {
        label {
            +"Timeframe"
            select {
                name = "prune"
                PruneAge.entries.forEach { age ->
                    option {
                        value = age.key
                        selected = age == (view.dialog?.age ?: PruneAge.DEFAULT)
                        +age.label
                    }
                }
            }
        }
        button { +"Review what's pruned…" }
    }
}

/**
 * The "are you sure?" dialog. It's the page itself, not a script's: the CSP allows none inline,
 * and it works without any. Cancel is a link back; the red button needs the box ticked first.
 */
internal fun FlowContent.pruneDialog(view: PruneView, dialog: PruneDialog) {
    div("modal-backdrop") { }
    div("modal") {
        attributes["role"] = "dialog"
        attributes["aria-modal"] = "true"
        attributes["aria-labelledby"] = "prune-title"
        h2 { id = "prune-title"; +"Are you sure?" }
        p { +"Timeframe: "; strong { +dialog.age.label } }
        if (dialog.count == 0) {
            p { +"Nothing in that timeframe that isn't pruned already." }
            div("actions") { a(href = "/revisions", classes = "button") { +"Close" } }
            return@div
        }
        val plan = dialog.plan
        val from = view.prunedThrough + 1
        val through = dialog.through
        p {
            +"These ${plural(dialog.count, "revision", "revisions")}, r$from–r$through"
            if (dialog.firstAt != null && dialog.lastAt != null) +" (${day(dialog.firstAt)} to ${day(dialog.lastAt)})"
            +", lose their older versions and can no longer be reverted:"
        }
        ul("list revisions prune-list") {
            dialog.shown.forEach { r ->
                li {
                    a(href = "/revisions/${r.rev}") { +"r${r.rev}" }
                    +" · ${r.deviceName} · ${day(r.createdAt)} · ${r.message}"
                }
            }
            if (dialog.count > dialog.shown.size) li("muted") { +"…and ${dialog.count - dialog.shown.size} older" }
        }
        if (plan == null) {
            view.blocker?.let { p("notice warn") { +it } }
            div("actions") { a(href = "/revisions", classes = "button") { +"Close" } }
            return@div
        }
        ul("prune-facts") {
            li { +"${plural(plan.versions, "old version", "old versions")} deleted" }
            if (plan.assets > 0) li { +"${plural(plan.assets, "deleted file", "deleted files")} removed for good" }
            li { strong { +bytes(plan.bytes) }; +" freed" }
        }
        p("muted small") { +"Files uploaded in the last 7 days stay until they're a week old, so a sync under way can't lose them." }
        p("prune-warning") {
            strong { +"This can't be undone. " }
            +"Pruned history is deleted, not hidden: nothing brings it back. Revisions after r$through stay revertible."
        }
        form(action = "/revisions/prune", method = FormMethod.post) {
            hiddenInput(name = "age") { value = dialog.age.key }
            hiddenInput(name = "through") { value = plan.through.toString() }
            label("check confirm") {
                input(InputType.checkBox, name = "confirm") { id = "prune-confirm"; required = true; value = "yes" }
                span { +"I understand this permanently deletes ${plural(dialog.count, "revision's", "revisions'")} older versions" }
            }
            div("actions") {
                a(href = "/revisions", classes = "button") { +"Cancel" }
                button(classes = "danger-fill") { +"Prune ${plural(dialog.count, "revision", "revisions")}" }
            }
        }
    }
}

private fun day(millis: Long) = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
    .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
