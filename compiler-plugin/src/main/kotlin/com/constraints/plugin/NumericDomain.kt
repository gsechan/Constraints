package com.constraints.plugin

/**
 * The representable range of a constrained value type -- [INT] or [LONG]. Interval arithmetic is
 * done over-approximately in [Long]; a result is widened to [Interval.UNKNOWN] whenever it would
 * overflow `Long` (caught via the `*Exact` helpers) OR leave [min]..[max]. That keeps the analysis
 * sound for both domains: an `Int` value whose math escapes 32 bits would wrap at runtime, and a
 * `Long` value whose math escapes 64 bits would wrap too -- in neither case can we trust the
 * over-approximation, so we forget it.
 *
 * Pure Kotlin (no FIR), so it is unit-testable on its own.
 */
internal class NumericDomain(val min: Long, val max: Long) {

    fun of(lo: Long, hi: Long): Interval =
        if (lo < min || hi > max) Interval.UNKNOWN else Interval(lo, hi)

    fun plus(a: Interval, b: Interval): Interval =
        if (a.isUnknown || b.isUnknown) Interval.UNKNOWN
        else safe { of(Math.addExact(a.min, b.min), Math.addExact(a.max, b.max)) }

    fun minus(a: Interval, b: Interval): Interval =
        if (a.isUnknown || b.isUnknown) Interval.UNKNOWN
        else safe { of(Math.subtractExact(a.min, b.max), Math.subtractExact(a.max, b.min)) }

    fun times(a: Interval, b: Interval): Interval =
        if (a.isUnknown || b.isUnknown) Interval.UNKNOWN
        else safe {
            val corners = longArrayOf(
                Math.multiplyExact(a.min, b.min), Math.multiplyExact(a.min, b.max),
                Math.multiplyExact(a.max, b.min), Math.multiplyExact(a.max, b.max),
            )
            of(corners.min(), corners.max())
        }

    fun div(a: Interval, b: Interval): Interval {
        if (a.isUnknown || b.isUnknown) return Interval.UNKNOWN
        // If the divisor range includes 0 we can't bound the result (and it might divide by zero).
        if (b.min <= 0 && b.max >= 0) return Interval.UNKNOWN
        // The one integer-division overflow is MIN_VALUE / -1; don't trust the wrapped value.
        if (a.min == Long.MIN_VALUE && b.min <= -1 && b.max >= -1) return Interval.UNKNOWN
        val corners = longArrayOf(a.min / b.min, a.min / b.max, a.max / b.min, a.max / b.max)
        return of(corners.min(), corners.max())
    }

    fun rem(a: Interval, b: Interval): Interval {
        if (a.isUnknown || b.isUnknown) return Interval.UNKNOWN
        // Divisor may be 0 -> can't bound (reported as a divide/modulo-by-zero error elsewhere).
        if (b.min <= 0 && b.max >= 0) return Interval.UNKNOWN
        return safe {
            // `%` keeps the dividend's sign with |result| <= |divisor| - 1, and is also bounded by
            // the dividend. safeAbs widens to UNKNOWN if a divisor bound is MIN_VALUE.
            val maxRemainder = maxOf(safeAbs(b.min), safeAbs(b.max)) - 1
            val lo = if (a.min < 0) maxOf(a.min, -maxRemainder) else 0L
            val hi = if (a.max > 0) minOf(a.max, maxRemainder) else 0L
            of(lo, hi)
        }
    }

    private inline fun safe(block: () -> Interval): Interval =
        try { block() } catch (e: ArithmeticException) { Interval.UNKNOWN }

    /** abs that throws (rather than overflowing to a negative) on MIN_VALUE, so [safe] forgets it. */
    private fun safeAbs(x: Long): Long =
        if (x == Long.MIN_VALUE) throw ArithmeticException("abs overflow") else if (x < 0) -x else x

    companion object {
        val INT = NumericDomain(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        val LONG = NumericDomain(Long.MIN_VALUE, Long.MAX_VALUE)
    }
}
