package com.constraints.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure residue-combination helper behind `@DivisibleBy`/`@LongDivisibleBy`.
 * The per-operator dispatch (in [inferResidueCall]) is exercised by the demo integration tests;
 * here we pin down the contracts that keep it sound: an unknown operand poisons the result, and
 * a Long-overflowing combination falls back to "unknown" rather than a wrong residue.
 */
class ResidueArithmeticTest {

    @Test
    fun `combines two known residues, reduced modulo the divisor`() {
        assertEquals(1L, remainderOp(2L, 3L, 4L) { a, b -> a + b })      // (2 + 3) mod 4 == 1
        assertEquals(2L, remainderOp(2L, 3L, 4L) { a, b -> a * b })      // (2 * 3) mod 4 == 2
        assertEquals(2L, remainderOp(1L, 2L, 3L) { a, b -> a - b })      // (1 - 2) mod 3 == 2 (floored)
    }

    @Test
    fun `is null when either residue is unknown`() {
        assertNull(remainderOp(null, 3L, 4L) { a, b -> a + b })
        assertNull(remainderOp(2L, null, 4L) { a, b -> a + b })
        assertNull(remainderOp(null, null, 4L) { a, b -> a + b })
    }

    @Test
    fun `is null when the combination overflows Long`() {
        // A huge Long divisor can make residues whose product overflows; that must be unprovable,
        // never a silently-wrapped (wrong) residue.
        assertNull(remainderOp(Long.MAX_VALUE, 2L, Long.MAX_VALUE) { a, b -> Math.multiplyExact(a, b) })
        assertNull(remainderOp(Long.MAX_VALUE, 1L, Long.MAX_VALUE) { a, b -> Math.addExact(a, b) })
    }
}
