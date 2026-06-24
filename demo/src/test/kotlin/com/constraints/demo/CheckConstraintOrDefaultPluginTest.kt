package com.constraints.demo

import com.constraints.DivisibleBy
import com.constraints.IntRange
import com.constraints.StringLength
import com.constraints.checkConstraintOrDefault
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `checkConstraintOrDefault(value, default)` -- the runtime analog of `?:`. The plugin injects the
 * same validators as `checkConstraint`, but a [com.constraints.ConstraintException] from any of them
 * is caught and `default` is returned instead.
 */
class CheckConstraintOrDefaultPluginTest {

    @Test
    fun `returns the value when it satisfies the constraint`() {
        @IntRange(0, 10) val x = checkConstraintOrDefault(5, 0)
        assertEquals(5, x)
    }

    @Test
    fun `returns the default when the value violates the constraint`() {
        @IntRange(0, 10) val x = checkConstraintOrDefault(20, 7)
        assertEquals(7, x)
    }

    @Test
    fun `falls back for a runtime value out of range`() {
        val input = 99
        @IntRange(0, 10) val x = checkConstraintOrDefault(input, 3)
        assertEquals(3, x)
    }

    @Test
    fun `works with a divisibility constraint`() {
        @DivisibleBy(2, 0) val a = checkConstraintOrDefault(5, 4) // 5 is odd -> falls back to 4
        assertEquals(4, a)
        @DivisibleBy(2, 0) val b = checkConstraintOrDefault(8, 4) // 8 is even -> kept
        assertEquals(8, b)
    }

    @Test
    fun `works with a string length constraint`() {
        @StringLength(1, 3) val ok = checkConstraintOrDefault("hi", "x")
        assertEquals("hi", ok)
        @StringLength(1, 3) val tooLong = checkConstraintOrDefault("hello", "x") // length 5 -> fallback
        assertEquals("x", tooLong)
    }
}
