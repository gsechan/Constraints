package com.constraints.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the [Interval] lattice and its [NumericDomain] arithmetic -- the pure
 * abstract-interpretation engine behind `@IntRange`/`@LongRange`. No compiler/FIR involved.
 * This is the soundness-critical core: a bug here (e.g. an interval claimed narrower than it
 * really is) would let the plugin "prove" an out-of-range assignment and wrongly elide the check.
 */
class IntervalTest {

    private val int = NumericDomain.INT
    private val long = NumericDomain.LONG

    @Test
    fun `point builds a singleton interval`() {
        assertEquals(Interval(5, 5), Interval.point(5))
    }

    @Test
    fun `isUnknown is true only for the top element`() {
        assertTrue(Interval.UNKNOWN.isUnknown)
        assertFalse(Interval(0, 5).isUnknown)
    }

    @Test
    fun `subsetOf holds only for fully-contained ranges`() {
        assertTrue(Interval(2, 5).subsetOf(Interval(0, 10)))
        assertTrue(Interval(0, 10).subsetOf(Interval(0, 10)))
        assertFalse(Interval(0, 10).subsetOf(Interval(2, 5)))
        assertFalse(Interval(-1, 5).subsetOf(Interval(0, 10)))
        assertFalse(Interval.UNKNOWN.subsetOf(Interval(0, 5))) // UNKNOWN is never a subset of a real range
    }

    @Test
    fun `overlaps is true when the ranges share any value`() {
        assertTrue(Interval(0, 5).overlaps(Interval(5, 10)))   // share exactly 5
        assertTrue(Interval(0, 10).overlaps(Interval(3, 4)))
        assertFalse(Interval(0, 4).overlaps(Interval(5, 10)))
    }

    // --- Int domain: arithmetic clamps to the 32-bit range ---

    @Test
    fun `int domain plus and minus shift the bounds`() {
        assertEquals(Interval(4, 6), int.plus(Interval(1, 2), Interval(3, 4)))
        assertEquals(Interval(3, 7), int.minus(Interval(5, 8), Interval(1, 2)))
    }

    @Test
    fun `int domain times uses extreme corner products`() {
        assertEquals(Interval(8, 15), int.times(Interval(2, 3), Interval(4, 5)))
        assertEquals(Interval(-12, 15), int.times(Interval(-2, 3), Interval(-4, 5)))
    }

    @Test
    fun `int domain div excludes a divisor spanning zero`() {
        assertEquals(Interval(2, 10), int.div(Interval(10, 20), Interval(2, 5)))
        assertEquals(Interval.UNKNOWN, int.div(Interval(10, 20), Interval(-1, 1)))
    }

    @Test
    fun `int domain rem bounds by the divisor and the dividend sign`() {
        assertEquals(Interval(0, 2), int.rem(Interval(0, 100), Interval(3, 3)))
        assertEquals(Interval(-2, 0), int.rem(Interval(-100, -1), Interval(3, 3)))
        assertEquals(Interval.UNKNOWN, int.rem(Interval(5, 5), Interval(-2, 2)))
    }

    @Test
    fun `int domain widens to UNKNOWN when arithmetic leaves the Int range`() {
        assertEquals(Interval.UNKNOWN, int.plus(Interval.point(Int.MAX_VALUE.toLong()), Interval.point(1)))
        assertEquals(Interval.UNKNOWN, int.minus(Interval.point(Int.MIN_VALUE.toLong()), Interval.point(1)))
        // A value beyond Int range is unknown for Int even if it fits in Long.
        assertEquals(Interval.UNKNOWN, int.of(0, Int.MAX_VALUE.toLong() + 1))
    }

    // --- Long domain: full 64-bit range, widening only on actual Long overflow ---

    @Test
    fun `long domain keeps values beyond the Int range`() {
        // This is the whole point of the Long domain: arithmetic past 2^31 is NOT widened away.
        assertEquals(Interval(1, 5_000_000_001L), long.plus(Interval(0, 5_000_000_000L), Interval.point(1)))
        assertEquals(Interval(0, Long.MAX_VALUE), long.of(0, Long.MAX_VALUE))
    }

    @Test
    fun `long domain widens to UNKNOWN on Long overflow`() {
        assertEquals(Interval.UNKNOWN, long.plus(Interval.point(Long.MAX_VALUE), Interval.point(1)))
        assertEquals(Interval.UNKNOWN, long.times(Interval.point(Long.MAX_VALUE), Interval.point(2)))
        assertEquals(Interval.UNKNOWN, long.div(Interval.point(Long.MIN_VALUE), Interval(-1, -1))) // MIN / -1 overflow
        assertEquals(Interval.UNKNOWN, long.rem(Interval.point(5), Interval.point(Long.MIN_VALUE))) // abs(MIN) overflow
    }

    @Test
    fun `UNKNOWN propagates through every operation`() {
        for (domain in listOf(int, long)) {
            assertEquals(Interval.UNKNOWN, domain.plus(Interval.UNKNOWN, Interval(1, 2)))
            assertEquals(Interval.UNKNOWN, domain.minus(Interval(1, 2), Interval.UNKNOWN))
            assertEquals(Interval.UNKNOWN, domain.times(Interval.UNKNOWN, Interval(2, 3)))
            assertEquals(Interval.UNKNOWN, domain.div(Interval.UNKNOWN, Interval(2, 3)))
            assertEquals(Interval.UNKNOWN, domain.rem(Interval.UNKNOWN, Interval(2, 3)))
        }
    }
}
