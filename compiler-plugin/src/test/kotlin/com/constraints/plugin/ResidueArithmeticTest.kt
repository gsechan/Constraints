package com.constraints.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure residue-combination helper behind `@DivisibleBy`. The per-operator
 * dispatch (which lives in [inferResidueCall]) is exercised by the demo integration tests;
 * here we pin down the null-propagation contract that makes an unknown operand poison the
 * whole result (so a partially-unknown expression is reported as unprovable, never falsely
 * proven).
 */
class ResidueArithmeticTest {

    @Test
    fun `combines two known residues with the given operation`() {
        assertEquals(5L, combineResidues(2L, 3L) { a, b -> a + b })
        assertEquals(6L, combineResidues(2L, 3L) { a, b -> a * b })
    }

    @Test
    fun `is null when either residue is unknown`() {
        assertNull(combineResidues(null, 3L) { a, b -> a + b })
        assertNull(combineResidues(2L, null) { a, b -> a + b })
        assertNull(combineResidues(null, null) { a, b -> a + b })
    }
}
