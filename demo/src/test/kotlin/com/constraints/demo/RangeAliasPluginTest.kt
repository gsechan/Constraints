package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.NegativeInt
import com.constraints.NonNegativeInt
import com.constraints.NonPositiveInt
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@IntRange` aliases: `@NegativeInt` (-MAX..-1), `@NonPositiveInt` (-MAX..0), and
 * `@NonNegativeInt` (0..MAX). Each behaves exactly like its underlying `@IntRange`:
 * provably-in-range assignments compile with no runtime cost, and the
 * `checkConstraint(value)` escape hatch defers the check to runtime.
 */
class RangeAliasPluginTest {

    // --- @NegativeInt : [-Int.MAX_VALUE, -1] ---

    @Test
    fun `negative value compiles`() {
        @NegativeInt val a = -5
        assertEquals(-5, a)
    }

    @Test
    fun `negative upper boundary -1 compiles`() {
        @NegativeInt val a = -1
        assertEquals(-1, a)
    }

    @Test
    fun `NegativeInt checkConstraint throws for zero`() {
        assertFailsWith<ConstraintException> {
            @NegativeInt val a = checkConstraint(0)
            println(a)
        }
    }

    // --- @NonPositiveInt : [-Int.MAX_VALUE, 0] ---

    @Test
    fun `zero compiles as non-positive`() {
        @NonPositiveInt val a = 0
        assertEquals(0, a)
    }

    @Test
    fun `negative compiles as non-positive`() {
        @NonPositiveInt val a = -5
        assertEquals(-5, a)
    }

    @Test
    fun `NonPositiveInt checkConstraint throws for a positive value`() {
        assertFailsWith<ConstraintException> {
            @NonPositiveInt val a = checkConstraint(1)
            println(a)
        }
    }

    // --- @NonNegativeInt : [0, Int.MAX_VALUE] ---

    @Test
    fun `zero compiles as non-negative`() {
        @NonNegativeInt val a = 0
        assertEquals(0, a)
    }

    @Test
    fun `positive compiles as non-negative`() {
        @NonNegativeInt val a = 5
        assertEquals(5, a)
    }

    @Test
    fun `NonNegativeInt checkConstraint throws for a negative value`() {
        assertFailsWith<ConstraintException> {
            @NonNegativeInt val a = checkConstraint(-1)
            println(a)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @NegativeInt    val a = 0    // 0 not in [-2147483647, -1]
    //   @NonPositiveInt val b = 1    // 1 not in [-2147483647, 0]
    //   @NonNegativeInt val c = -1   // -1 not in [0, 2147483647]
    // -----------------------------------------------------------------------
}
