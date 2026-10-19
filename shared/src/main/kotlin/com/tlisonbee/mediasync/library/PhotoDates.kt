package com.tlisonbee.mediasync.library

import java.time.LocalDate

/**
 * A photo's date, as precise as it's known: a year ("2019"), a month ("2019-07") or a day
 * ("2019-07-14"). It names the photo's folder and leads its file name.
 */
object PhotoDates {
    private val DATE = Regex("""(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?""")

    /** Eight digits that read as a day, with optional separators: IMG_20240714_…, Screenshot_2024-07-14-…. */
    private val IN_NAME = Regex("""(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?!\d)""")

    /** A date (as above) leading a name, with what separates it from the rest. */
    private val LEADING = Regex("""^\d{4}(?:-\d{2}(?:-\d{2})?)?[ _-]+""")

    /**
     * [text] as one of the three forms, or null if it isn't a real date. Also reads EXIF's
     * "2019:07:14 12:30:00", ISO timestamps and slashes.
     */
    fun normalize(text: String): String? {
        val day = text.trim().replace(':', '-').replace('/', '-').substringBefore(' ').substringBefore('T')
        val (y, m, d) = DATE.matchEntire(day)?.destructured ?: return null
        if (y.toInt() !in 1826..2999) return null // the oldest surviving photograph is from 1826
        if (m.isEmpty()) return y
        if (m.toInt() !in 1..12) return null
        if (d.isEmpty()) return "$y-$m"
        runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() ?: return null
        return "$y-$m-$d"
    }

    /** The day a camera or app wrote into a file name (IMG_20240714_153012, PXL_…, IMG-20240714-WA0001), if any. */
    fun fromName(name: String): String? {
        val (y, m, d) = IN_NAME.find(name)?.destructured ?: return null
        return normalize("$y-$m-$d")
    }

    /** [name] without a date leading it, which the photo's path puts back from its date field. */
    fun withoutDate(name: String): String = name.replace(LEADING, "")
}
