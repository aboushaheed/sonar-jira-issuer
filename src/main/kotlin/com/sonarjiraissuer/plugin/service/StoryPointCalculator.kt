package com.sonarjiraissuer.plugin.service

import kotlin.math.ceil

/**
 * Converts SonarQube remediation effort (minutes) to Jira Story Points.
 *
 * ## Rule
 * - **1 Story Point = 1 working day = 8 hours = 480 minutes**
 *
 * ## Rounding strategy
 * Round **up** to the nearest **0.5 SP**.
 * This is the most developer-friendly strategy: no issue is under-estimated,
 * and increments of 0.5 SP (half-day) match common team planning granularity.
 *
 * | Minutes | Raw SP  | Rounded SP |
 * |---------|---------|------------|
 * |    0    |  0.000  |   0.0      |
 * |    1    |  0.002  |   0.5      |
 * |  240    |  0.500  |   0.5      |
 * |  241    |  0.502  |   1.0      |
 * |  480    |  1.000  |   1.0      |
 * |  481    |  1.002  |   1.5      |
 * |  960    |  2.000  |   2.0      |
 */
object StoryPointCalculator {

    private const val MINUTES_PER_POINT = 480.0

    /**
     * Convert [totalMinutes] to story points, rounded up to the nearest 0.5.
     * Returns `0.0` for zero or negative inputs.
     */
    fun calculate(totalMinutes: Long): Double {
        if (totalMinutes <= 0L) return 0.0
        val rawPoints = totalMinutes / MINUTES_PER_POINT
        return roundUpToHalf(rawPoints)
    }

    /**
     * Round [value] up to the nearest 0.5.
     * Algorithm: multiply by 2, apply ceiling, divide by 2.
     *
     * Examples: 0.1 → 0.5 | 0.5 → 0.5 | 0.6 → 1.0 | 1.1 → 1.5
     */
    fun roundUpToHalf(value: Double): Double {
        if (value <= 0.0) return 0.0
        return ceil(value * 2.0) / 2.0
    }

    /**
     * Format story points for display, removing unnecessary ".0" suffix.
     * Examples: 1.0 → "1" | 1.5 → "1.5" | 2.0 → "2"
     */
    fun format(sp: Double): String {
        return if (sp == sp.toLong().toDouble()) sp.toLong().toString() else sp.toString()
    }
}
