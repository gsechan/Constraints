package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.DivisibleBy
import com.constraints.Even
import com.constraints.Odd
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@DivisibleBy(divisor, remainder)` -- a COMPILE-TIME constraint (like `@IntRange`). It passes
 * when `value.mod(divisor) == remainder` using floored modulo, so it composes through arithmetic
 * and the plugin can prove it statically for literals, congruence-preserving arithmetic, and
 * reads of other `@DivisibleBy` values. Anything it can't prove needs `checkConstraint(value)`.
 */
class DivisibleByPluginTest {

    @Test
    fun `divisible literal compiles`() {
        @DivisibleBy(2, 0) val a = 4
        assertEquals(4, a)
    }

    @Test
    fun `remainder defaults to zero`() {
        @DivisibleBy(3) val a = 9
        assertEquals(9, a)
    }

    @Test
    fun `non-zero remainder compiles`() {
        @DivisibleBy(3, 1) val a = 7 // 7 mod 3 == 1
        assertEquals(7, a)
    }

    @Test
    fun `plus preserves divisibility`() {
        @DivisibleBy(2, 0) val a = 4
        @DivisibleBy(2, 0) val b = a + 2 // 0 + 0 == 0 (mod 2)
        assertEquals(6, b)
    }

    @Test
    fun `times preserves divisibility`() {
        @DivisibleBy(3, 0) val a = 9
        @DivisibleBy(3, 0) val b = a * 4 // 0 * anything == 0 (mod 3)
        assertEquals(36, b)
    }

    @Test
    fun `arithmetic shifts the remainder`() {
        @DivisibleBy(2, 0) val even = 4
        @DivisibleBy(2, 1) val odd = even + 1 // 0 + 1 == 1 (mod 2)
        assertEquals(5, odd)
    }

    @Test
    fun `divisible by four is divisible by two`() {
        // Transfer through a compatible divisor: 2 divides 4, so @DivisibleBy(4,0) implies (2,0).
        @DivisibleBy(4, 0) val a = 8
        @DivisibleBy(2, 0) val b = a
        assertEquals(8, b)
    }

    @Test
    fun `negative value uses floored modulo`() {
        // -1 is congruent to 2 modulo 3 (floored), even though -1 % 3 == -1.
        @DivisibleBy(3, 2) val n = -1
        assertEquals(-1, n)
    }

    @Test
    fun `checkConstraint allows a divisible runtime value`() {
        @DivisibleBy(3, 0) val x = checkConstraint(9)
        assertEquals(9, x)
    }

    @Test
    fun `checkConstraint throws for a non-divisible runtime value`() {
        assertFailsWith<ConstraintException> {
            @DivisibleBy(3, 0) val x = checkConstraint(10) // 10 mod 3 == 1
            println(x)
        }
    }

    // --- @Even / @Odd aliases (for @DivisibleBy(2, 0) / @DivisibleBy(2, 1)) ---

    @Test
    fun `even alias proves an even literal`() {
        @Even val a = 4
        assertEquals(4, a)
    }

    @Test
    fun `odd alias proves an odd literal`() {
        @Odd val a = 7
        assertEquals(7, a)
    }

    @Test
    fun `even plus one is odd`() {
        // The aliases compose through the same residue analysis: even + 1 is provably odd.
        @Even val e = 4
        @Odd val o = e + 1
        assertEquals(5, o)
    }

    @Test
    fun `even alias defers to runtime via checkConstraint`() {
        @Even val x = checkConstraint(6)
        assertEquals(6, x)
        assertFailsWith<ConstraintException> {
            @Even val y = checkConstraint(7)
            println(y)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @DivisibleBy(2, 0) val x = 5            // 5 mod 2 == 1: can never be valid
    //   @DivisibleBy(2, 0) val y = someInput    // residue unknown: needs checkConstraint
    //   @DivisibleBy(2, 0) var a = 4; a++       // a becomes 5, which is 1 mod 2
    //   @Odd val z = 4                          // 4 is even: can never be valid
    // -----------------------------------------------------------------------
}
