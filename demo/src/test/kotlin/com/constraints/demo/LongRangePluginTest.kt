package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.LongRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@LongRange` -- the Long counterpart of `@IntRange`. Same compile-time proving (literals,
 * narrower `@LongRange` values, interval-safe arithmetic, or `checkConstraint`), but over the
 * full 64-bit range, so values beyond `Int.MAX_VALUE` are handled exactly.
 */
class LongRangePluginTest {

    @Test
    fun `in-range literal compiles`() {
        @LongRange(0, 100) val a = 50L
        assertEquals(50L, a)
    }

    @Test
    fun `value beyond the Int range compiles`() {
        @LongRange(0, 10_000_000_000L) val a = 5_000_000_000L
        assertEquals(5_000_000_000L, a)
    }

    @Test
    fun `provably-in-range arithmetic compiles`() {
        @LongRange(0, 100) val a = 50L
        @LongRange(0, 200) val b = a + 1 // a is in [0,100], so a+1 is in [1,101]
        assertEquals(51L, b)
    }

    @Test
    fun `transfer between same-range values compiles`() {
        @LongRange(0, 100) val a = 50L
        @LongRange(0, 100) val b = a
        assertEquals(50L, b)
    }

    @Test
    fun `checkConstraint allows an in-range runtime value`() {
        @LongRange(0, 100) val x = checkConstraint(50L)
        assertEquals(50L, x)
    }

    @Test
    fun `checkConstraint throws for an out-of-range runtime value`() {
        assertFailsWith<ConstraintException> {
            @LongRange(0, 100) val x = checkConstraint(200L)
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @LongRange(0, 100) val x = 200L         // 200 is out of [0, 100]
    //   @LongRange(0, 100) val y = someLong     // range unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
