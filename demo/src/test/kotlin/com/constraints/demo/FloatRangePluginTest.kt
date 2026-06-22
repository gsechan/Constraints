package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.FloatRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@FloatRange` -- the Float counterpart of `@IntRange`. Proven at compile time for literals and
 * same-range transfers; `checkConstraint(value)` defers to runtime. Floating point arithmetic
 * results are not proven statically (rounding makes sound over-approximation impractical).
 */
class FloatRangePluginTest {

    @Test
    fun `in-range float literal compiles`() {
        @FloatRange(0.0f, 1.0f) val a = 0.5f
        assertEquals(0.5f, a)
    }

    @Test
    fun `lower boundary compiles`() {
        @FloatRange(0.0f, 1.0f) val a = 0.0f
        assertEquals(0.0f, a)
    }

    @Test
    fun `upper boundary compiles`() {
        @FloatRange(0.0f, 1.0f) val a = 1.0f
        assertEquals(1.0f, a)
    }

    @Test
    fun `transfer between same-range values compiles`() {
        @FloatRange(0.0f, 1.0f) val a = 0.5f
        @FloatRange(0.0f, 1.0f) val b = a
        assertEquals(0.5f, b)
    }

    @Test
    fun `arithmetic result proven in range`() {
        // [0.0, 0.5] + [0.0, 0.5] = [0.0, 1.0] -- proven subset of [0.0, 1.0]
        @FloatRange(0.0f, 0.5f) val a = 0.25f
        @FloatRange(0.0f, 0.5f) val b = 0.25f
        @FloatRange(0.0f, 1.0f) val c = a + b
        assertEquals(0.5f, c)
    }

    @Test
    fun `unary minus flips the range`() {
        // -[0.0, 1.0] = [-1.0, 0.0]
        @FloatRange(0.0f, 1.0f) val a = 0.5f
        @FloatRange(-1.0f, 0.0f) val b = -a
        assertEquals(-0.5f, b)
    }

    @Test
    fun `checkConstraint allows an in-range runtime value`() {
        @FloatRange(0.0f, 1.0f) val x = checkConstraint(0.5f)
        assertEquals(0.5f, x)
    }

    @Test
    fun `checkConstraint throws for a value above the range`() {
        assertFailsWith<ConstraintException> {
            @FloatRange(0.0f, 1.0f) val x = checkConstraint(1.5f)
            println(x)
        }
    }

    @Test
    fun `checkConstraint throws for a value below the range`() {
        assertFailsWith<ConstraintException> {
            @FloatRange(0.0f, 1.0f) val x = checkConstraint(-0.1f)
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @FloatRange(0.0f, 1.0f) val x = 1.5f       // 1.5 is out of [0.0, 1.0]
    //   @FloatRange(0.0f, 1.0f) val y = someFloat   // range unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
