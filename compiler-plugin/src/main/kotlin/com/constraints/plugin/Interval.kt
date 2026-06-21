package com.constraints.plugin

/**
 * Interval lattice -- the value-range half of the static reasoning. Pure Kotlin, so it is fully
 * testable on its own and independent of the (more volatile) FIR API. The arithmetic over
 * intervals lives in [NumericDomain] (it needs the value type's bounds for overflow/clamping).
 */
internal data class Interval(val min: Long, val max: Long) {
    val isUnknown: Boolean get() = this == UNKNOWN

    fun subsetOf(other: Interval): Boolean = min >= other.min && max <= other.max

    /** True if the two ranges share at least one value (so the assignment *could* be valid). */
    fun overlaps(other: Interval): Boolean = min <= other.max && other.min <= max

    companion object {
        /** "Could be anything" -- the top of the lattice; never a subset of a real range. */
        val UNKNOWN = Interval(Long.MIN_VALUE, Long.MAX_VALUE)

        fun point(v: Long) = Interval(v, v)
    }
}
