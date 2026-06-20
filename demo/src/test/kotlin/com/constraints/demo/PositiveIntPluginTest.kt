package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.PositiveInt
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@PositiveInt` is an alias for `@IntRange(1, Int.MAX_VALUE)`. It behaves exactly
 * like `@IntRange`: provably-positive assignments compile with no runtime cost, and
 * the `checkConstraint(value)` escape hatch defers the check to runtime. Cases that
 * must NOT compile are documented at the end.
 */
class PositiveIntPluginTest {

    @Test
    fun `positive literal compiles`() {
        @PositiveInt val a = 5
        assertEquals(5, a)
    }

    @Test
    fun `lower boundary 1 compiles`() {
        @PositiveInt val a = 1                 // 1 is the inclusive minimum
        assertEquals(1, a)
    }

    @Test
    fun `a narrower IntRange is assignable to PositiveInt`() {
        @IntRange(1, 100) var x = 50
        @PositiveInt var p = x                 // [1,100] is a subset of [1, Int.MAX_VALUE] -> proven
        assertEquals(50, p)
    }

    @Test
    fun `dynamic positive value via checkConstraint compiles and passes`() {
        val raw = 7
        @PositiveInt val a = checkConstraint(raw)   // escape hatch: validated at runtime
        assertEquals(7, a)
    }

    @Test
    fun `checkConstraint throws for zero`() {
        assertFailsWith<ConstraintException> {
            @PositiveInt val a = checkConstraint(0)
            println(a)
        }
    }

    @Test
    fun `checkConstraint throws for a negative value`() {
        val raw = -5
        assertFailsWith<ConstraintException> {
            @PositiveInt val a = checkConstraint(raw)
            println(a)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //
    //   @PositiveInt val a = 0     // ERROR: 0 is not within [1, 2147483647]
    //   @PositiveInt val b = -1    // ERROR: -1 is not within [1, 2147483647]
    // -----------------------------------------------------------------------
}
