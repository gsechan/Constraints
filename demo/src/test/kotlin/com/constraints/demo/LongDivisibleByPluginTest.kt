package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.LongDivisibleBy
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@LongDivisibleBy` -- the Long counterpart of `@DivisibleBy`. Same floored-modulo semantics and
 * compile-time congruence proving, over the full 64-bit range (residue arithmetic that would
 * overflow Long falls back to `checkConstraint` rather than a wrong, unsound result).
 */
class LongDivisibleByPluginTest {

    @Test
    fun `divisible literal compiles`() {
        @LongDivisibleBy(2, 0) val a = 4L
        assertEquals(4L, a)
    }

    @Test
    fun `remainder defaults to zero`() {
        @LongDivisibleBy(3) val a = 9L
        assertEquals(9L, a)
    }

    @Test
    fun `large divisor beyond the Int range compiles`() {
        @LongDivisibleBy(10_000_000_000L, 0) val a = 20_000_000_000L // 2e10 mod 1e10 == 0
        assertEquals(20_000_000_000L, a)
    }

    @Test
    fun `plus preserves divisibility`() {
        @LongDivisibleBy(2, 0) val a = 4L
        @LongDivisibleBy(2, 0) val b = a + 2 // 0 + 0 == 0 (mod 2)
        assertEquals(6L, b)
    }

    @Test
    fun `checkConstraint allows a divisible runtime value`() {
        @LongDivisibleBy(3, 0) val x = checkConstraint(9L)
        assertEquals(9L, x)
    }

    @Test
    fun `checkConstraint throws for a non-divisible runtime value`() {
        assertFailsWith<ConstraintException> {
            @LongDivisibleBy(3, 0) val x = checkConstraint(10L) // 10 mod 3 == 1
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @LongDivisibleBy(2, 0) val x = 5L          // 5 mod 2 == 1: can never be valid
    //   @LongDivisibleBy(2, 0) val y = someLong    // residue unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
