package com.constraints.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the [Interval] lattice -- the pure abstract-interpretation engine behind
 * `@IntRange`. No compiler/FIR involved. This is the soundness-critical core: a bug here
 * (e.g. an interval claimed narrower than it really is) would let the plugin "prove" an
 * assignment that can actually be out of range and wrongly elide the runtime check.
 */
class IntervalTest {

    @Test
    fun `point and of build the expected intervals`() {
        assertEquals(Interval(5, 5), Interval.point(5))
        assertEquals(Interval(0, 10), Interval.of(0, 10))
    }

    @Test
    fun `of widens to UNKNOWN when it leaves the Int range`() {
        // Interval math is done in Long; anything that escapes 32 bits can wrap at runtime,
        // so it must be treated as unknown rather than a value that can't actually occur.
        assertEquals(Interval.UNKNOWN, Interval.of(Int.MAX_VALUE.toLong() + 1, Int.MAX_VALUE.toLong() + 2))
        assertEquals(Interval.UNKNOWN, Interval.of(Int.MIN_VALUE.toLong() - 1, 0))
        assertEquals(Interval(0, Int.MAX_VALUE.toLong()), Interval.of(0, Int.MAX_VALUE.toLong()))
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
        // UNKNOWN is never a subset of a real range.
        assertFalse(Interval.UNKNOWN.subsetOf(Interval(0, 5)))
    }

    @Test
    fun `overlaps is true when the ranges share any value`() {
        assertTrue(Interval(0, 5).overlaps(Interval(5, 10)))   // share exactly 5
        assertTrue(Interval(0, 10).overlaps(Interval(3, 4)))
        assertFalse(Interval(0, 4).overlaps(Interval(5, 10)))
    }

    @Test
    fun `plus and minus shift the bounds`() {
        assertEquals(Interval(4, 6), Interval(1, 2) + Interval(3, 4))
        assertEquals(Interval(3, 7), Interval(5, 8) - Interval(1, 2))
    }

    @Test
    fun `times uses the extreme corner products`() {
        assertEquals(Interval(8, 15), Interval(2, 3) * Interval(4, 5))
        // Mixed signs: min/max come from whichever corner products are extreme.
        assertEquals(Interval(-12, 15), Interval(-2, 3) * Interval(-4, 5))
    }

    @Test
    fun `div excludes a divisor that spans zero`() {
        assertEquals(Interval(2, 10), Interval(10, 20) / Interval(2, 5))
        assertEquals(Interval.UNKNOWN, Interval(10, 20) / Interval(-1, 1))
    }

    @Test
    fun `rem bounds the result by the divisor and the dividend sign`() {
        assertEquals(Interval(0, 2), Interval(0, 100) % Interval(3, 3))
        assertEquals(Interval(-2, 0), Interval(-100, -1) % Interval(3, 3))
        assertEquals(Interval.UNKNOWN, Interval(5, 5) % Interval(-2, 2)) // divisor spans 0
    }

    @Test
    fun `UNKNOWN propagates through every operation`() {
        assertEquals(Interval.UNKNOWN, Interval.UNKNOWN + Interval(1, 2))
        assertEquals(Interval.UNKNOWN, Interval(1, 2) + Interval.UNKNOWN)
        assertEquals(Interval.UNKNOWN, Interval.UNKNOWN - Interval(1, 2))
        assertEquals(Interval.UNKNOWN, Interval.UNKNOWN * Interval(2, 3))
        assertEquals(Interval.UNKNOWN, Interval.UNKNOWN / Interval(2, 3))
        assertEquals(Interval.UNKNOWN, Interval.UNKNOWN % Interval(2, 3))
    }

    @Test
    fun `arithmetic overflowing the Int range widens to UNKNOWN`() {
        assertEquals(Interval.UNKNOWN, Interval.point(Int.MAX_VALUE.toLong()) + Interval.point(1))
        assertEquals(Interval.UNKNOWN, Interval.point(Int.MIN_VALUE.toLong()) - Interval.point(1))
    }
}
