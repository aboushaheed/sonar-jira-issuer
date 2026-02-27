package com.sonarjiraissuer.plugin.util

/**
 * Parses SonarQube effort strings into a total number of minutes.
 *
 * SonarQube effort format: `[Xd] [Xh] [Xmin]`
 * All components are optional and can appear in any combination.
 *
 * Working-day convention: **1 day = 8 hours = 480 minutes** (same as SonarQube).
 *
 * Examples:
 * ```
 * "5min"          →   5
 * "1h"            →  60
 * "2h 30min"      → 150
 * "1d"            → 480
 * "1d 2h"         → 600
 * "1d 2h 30min"   → 630
 * null / ""       →   0
 * ```
 */
object EffortParser {

    const val MINUTES_PER_HOUR: Long = 60L
    const val HOURS_PER_DAY: Long    = 8L
    const val MINUTES_PER_DAY: Long  = HOURS_PER_DAY * MINUTES_PER_HOUR

    private val DAYS_REGEX    = Regex("""(\d+)\s*d""")
    private val HOURS_REGEX   = Regex("""(\d+)\s*h""")
    private val MINUTES_REGEX = Regex("""(\d+)\s*min""")

    /**
     * Parse [effort] into total minutes.
     * Returns `0` if the input is `null`, blank, or contains no recognised tokens.
     */
    fun parseToMinutes(effort: String?): Long {
        if (effort.isNullOrBlank()) return 0L

        val days    = DAYS_REGEX.find(effort)?.groupValues?.get(1)?.toLongOrNull()    ?: 0L
        val hours   = HOURS_REGEX.find(effort)?.groupValues?.get(1)?.toLongOrNull()   ?: 0L
        val minutes = MINUTES_REGEX.find(effort)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        return days * MINUTES_PER_DAY + hours * MINUTES_PER_HOUR + minutes
    }

    /**
     * Format [totalMinutes] back into a human-readable effort string.
     * Returns `"0min"` when [totalMinutes] is zero or negative.
     *
     * Examples:
     * ```
     *   480 → "1d"
     *   630 → "1d 2h 30min"
     *    90 → "1h 30min"
     * ```
     */
    fun formatMinutes(totalMinutes: Long): String {
        if (totalMinutes <= 0L) return "0min"

        val days    = totalMinutes / MINUTES_PER_DAY
        val rem1    = totalMinutes % MINUTES_PER_DAY
        val hours   = rem1 / MINUTES_PER_HOUR
        val minutes = rem1 % MINUTES_PER_HOUR

        return buildString {
            if (days > 0)    append("${days}d ")
            if (hours > 0)   append("${hours}h ")
            if (minutes > 0) append("${minutes}min")
        }.trim()
    }
}
