package com.constraints.plugin

/**
 * Interval lattice for floating point range constraints (`@FloatRange`, `@DoubleRange`). Backed by
 * [Double] rather than [Long] so bounds are exact for both Float and Double annotations.
 *
 * Arithmetic operations are provided and used for compile-time proving wherever possible. The
 * bounds produced are a best-effort over-approximation: the user accepts that minor floating point
 * rounding may mean a boundary-hugging value is proved in range when it rounds just outside (or
 * vice versa). Any operation that would produce NaN or infinite bounds widens to [UNKNOWN] instead
 * of propagating a meaningless bound.
 *
 * Pure Kotlin (no FIR), unit-testable on its own.
 */
internal data class DoubleInterval(val min: Double, val max: Double) {
    val isUnknown: Boolean get() = this == UNKNOWN

    fun subsetOf(other: DoubleInterval): Boolean = min >= other.min && max <= other.max

    /** True if the two ranges share at least one value (so the assignment *could* be valid). */
    fun overlaps(other: DoubleInterval): Boolean = min <= other.max && other.min <= max

    operator fun unaryMinus(): DoubleInterval =
        if (isUnknown) UNKNOWN else safe { DoubleInterval(-max, -min) }

    operator fun plus(other: DoubleInterval): DoubleInterval =
        if (isUnknown || other.isUnknown) UNKNOWN
        else safe { DoubleInterval(min + other.min, max + other.max) }

    operator fun minus(other: DoubleInterval): DoubleInterval =
        if (isUnknown || other.isUnknown) UNKNOWN
        else safe { DoubleInterval(min - other.max, max - other.min) }

    operator fun times(other: DoubleInterval): DoubleInterval {
        if (isUnknown || other.isUnknown) return UNKNOWN
        val corners = doubleArrayOf(min * other.min, min * other.max, max * other.min, max * other.max)
        return safe { DoubleInterval(corners.min(), corners.max()) }
    }

    operator fun div(other: DoubleInterval): DoubleInterval {
        if (isUnknown || other.isUnknown) return UNKNOWN
        // If the divisor range includes 0 the result could be ±Infinity.
        if (other.min <= 0.0 && other.max >= 0.0) return UNKNOWN
        val corners = doubleArrayOf(min / other.min, min / other.max, max / other.min, max / other.max)
        return safe { DoubleInterval(corners.min(), corners.max()) }
    }

    companion object {
        /** "Could be anything" -- includes NaN, ±Infinity; never a subset of a finite range. */
        val UNKNOWN = DoubleInterval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

        fun point(v: Double) = DoubleInterval(v, v)

        /**
         * Returns [result] if both its bounds are finite; otherwise widens to [UNKNOWN]. Finite
         * bounds mean the arithmetic stayed within the representable Double range -- if either
         * bound overflowed to Infinity or became NaN we can't make useful claims.
         */
        internal fun safe(block: () -> DoubleInterval): DoubleInterval {
            val result = block()
            return if (result.min.isFinite() && result.max.isFinite()) result else UNKNOWN
        }
    }
}
