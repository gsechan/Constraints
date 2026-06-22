package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.DoubleRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@DoubleRange` -- the Double counterpart of `@IntRange`. Proven at compile time for literals and
 * same-range transfers; `checkConstraint(value)` defers to runtime. Floating point arithmetic
 * results are not proven statically (rounding makes sound over-approximation impractical).
 */
class DoubleRangePluginTest {

    @Test
    fun `in-range double literal compiles`() {
        @DoubleRange(0.0, 1.0) val a = 0.5
        assertEquals(0.5, a)
    }

    @Test
    fun `lower boundary compiles`() {
        @DoubleRange(0.0, 1.0) val a = 0.0
        assertEquals(0.0, a)
    }

    @Test
    fun `upper boundary compiles`() {
        @DoubleRange(0.0, 1.0) val a = 1.0
        assertEquals(1.0, a)
    }

    @Test
    fun `transfer between same-range values compiles`() {
        @DoubleRange(0.0, 1.0) val a = 0.5
        @DoubleRange(0.0, 1.0) val b = a
        assertEquals(0.5, b)
    }

    @Test
    fun `arithmetic result proven in range`() {
        // [0.0, 0.5] + [0.0, 0.5] = [0.0, 1.0] -- proven subset of [0.0, 1.0]
        @DoubleRange(0.0, 0.5) val a = 0.25
        @DoubleRange(0.0, 0.5) val b = 0.25
        @DoubleRange(0.0, 1.0) val c = a + b
        assertEquals(0.5, c)
    }

    @Test
    fun `multiplication proven in range`() {
        // [0.0, 1.0] * [0.0, 1.0] = [0.0, 1.0]
        @DoubleRange(0.0, 1.0) val a = 0.5
        @DoubleRange(0.0, 1.0) val b = 0.8
        @DoubleRange(0.0, 1.0) val c = a * b
        assertEquals(0.4, c)
    }

    @Test
    fun `unary minus flips the range`() {
        // -[0.0, 1.0] = [-1.0, 0.0]
        @DoubleRange(0.0, 1.0) val a = 0.5
        @DoubleRange(-1.0, 0.0) val b = -a
        assertEquals(-0.5, b)
    }

    @Test
    fun `checkConstraint allows an in-range runtime value`() {
        @DoubleRange(0.0, 1.0) val x = checkConstraint(0.5)
        assertEquals(0.5, x)
    }

    @Test
    fun `checkConstraint throws for a value above the range`() {
        assertFailsWith<ConstraintException> {
            @DoubleRange(0.0, 1.0) val x = checkConstraint(1.5)
            println(x)
        }
    }

    @Test
    fun `checkConstraint throws for a value below the range`() {
        assertFailsWith<ConstraintException> {
            @DoubleRange(0.0, 1.0) val x = checkConstraint(-0.1)
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @DoubleRange(0.0, 1.0) val x = 1.5       // 1.5 is out of [0.0, 1.0]
    //   @DoubleRange(0.0, 1.0) val y = someDouble // range unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
