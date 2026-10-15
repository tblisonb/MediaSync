package com.tlisonbee.mediasync.library

/**
 * Field-by-field three-way merge. A key missing from a map means the field is absent. Where both
 * sides changed a field differently, theirs (the server's) is kept and the field is reported.
 */
object ThreeWayMerge {
    data class Result<T>(val merged: Map<String, T>, val conflicts: Set<String>)

    /** With no [base] (it was pruned), every field the two sides disagree on is a conflict. */
    fun <T> merge(base: Map<String, T>?, ours: Map<String, T>, theirs: Map<String, T>): Result<T> {
        val merged = LinkedHashMap<String, T>()
        val conflicts = LinkedHashSet<String>()
        for (key in (theirs.keys + ours.keys)) {
            val o = ours[key]
            val t = theirs[key]
            val value = when {
                o == t -> t
                base == null -> t.also { conflicts += key }
                base[key] == o -> t
                base[key] == t -> o
                else -> t.also { conflicts += key }
            }
            if (value != null) merged[key] = value
        }
        return Result(merged, conflicts)
    }
}
