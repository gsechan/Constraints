package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.DivisibleBy
import com.constraints.IntRange
import com.constraints.Size
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Return range [2, 5], used to show a constrained-call element is proven. */
private fun smallPositive(): @IntRange(2, 5) Int = 3

/**
 * Element-type constraints work on `Array<T>` exactly like on collections -- every built-in
 * constraint, builder-literal static proof, hard errors for provably-bad elements, transfer, and
 * recursive nesting (`Array<@Size(..) Array<@IntRange(..) Int>>`).
 */
class ArrayElementTypeConstraintPluginTest {

    // --- @IntRange on array elements ---

    @Test
    fun `arrayOf of in-range literals compiles without checkConstraint`() {
        val xs: Array<@IntRange(0, 10) Int> = arrayOf(1, 5, 9)
        assertEquals(3, xs.size)
    }

    @Test
    fun `single-element arrayOf is proven`() {
        val xs: Array<@IntRange(0, 10) Int> = arrayOf(7)
        assertEquals(7, xs[0])
    }

    @Test
    fun `empty array builder compiles`() {
        val xs: Array<@IntRange(0, 10) Int> = emptyArray()
        assertEquals(0, xs.size)
    }

    @Test
    fun `constrained-call element is proven`() {
        // smallPositive() returns @IntRange(2, 5) ⊆ [0, 10].
        val xs: Array<@IntRange(0, 10) Int> = arrayOf(smallPositive())
        assertEquals(3, xs[0])
    }

    @Test
    fun `checkConstraint validates each element at runtime`() {
        val xs: Array<@IntRange(0, 10) Int> = checkConstraint(arrayOf(1, 5, 9))
        assertEquals(3, xs.size)
    }

    @Test
    fun `checkConstraint throws when an element is out of range`() {
        assertFailsWith<ConstraintException> {
            val xs: Array<@IntRange(0, 10) Int> = checkConstraint(arrayOf(1, 20, 3))
            println(xs)
        }
    }

    @Test
    fun `transfer between arrays with the same element-type constraint compiles`() {
        val a: Array<@IntRange(0, 10) Int> = arrayOf(1, 2)
        val b: Array<@IntRange(0, 10) Int> = a
        assertEquals(2, b.size)
    }

    // --- Other built-in constraints work as array element constraints ---

    @Test
    fun `divisibility on array elements`() {
        val xs: Array<@DivisibleBy(2, 0) Int> = arrayOf(2, 4, 6)
        assertEquals(3, xs.size)
    }

    @Test
    fun `size on string array elements throws for an over-length element`() {
        assertFailsWith<ConstraintException> {
            val xs: Array<@Size(1, 3) String> = checkConstraint(arrayOf("ok", "toolong"))
            println(xs)
        }
    }

    // --- Recursive nesting: Array<@Size Array<@IntRange Int>> ---

    @Test
    fun `nested arrays of provably-valid literals compile`() {
        val grid: Array<@Size(1, 1) Array<@IntRange(1, 10) Int>> = arrayOf(arrayOf(5))
        assertEquals(5, grid[0][0])
    }

    @Test
    fun `nested array checkConstraint passes for valid values`() {
        val grid: Array<@Size(1, 2) Array<@IntRange(1, 10) Int>> =
            checkConstraint(arrayOf(arrayOf(2, 3), arrayOf(4)))
        assertEquals(2, grid.size)
    }

    @Test
    fun `nested array checkConstraint throws when an inner element is out of range`() {
        assertFailsWith<ConstraintException> {
            val grid: Array<@Size(1, 2) Array<@IntRange(1, 10) Int>> =
                checkConstraint(arrayOf(arrayOf(3, 20)))   // 20 not in [1, 10]
            println(grid)
        }
    }

    @Test
    fun `nested array checkConstraint throws when an inner array violates its size`() {
        assertFailsWith<ConstraintException> {
            val grid: Array<@Size(1, 2) Array<@IntRange(1, 10) Int>> =
                checkConstraint(arrayOf(arrayOf(1, 2, 3)))  // inner size 3 not in [1, 2]
            println(grid)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile (see ArrayElementTypeCompileFail.kt):
    //   val x: Array<@IntRange(0, 10) Int> = arrayOf(1, 20, 3)   // 20 provably out of range: HARD error
    //   val y: Array<@Size(1, 1) Array<@IntRange(1, 10) Int>> = arrayOf(arrayOf(20)) // inner HARD error
    // -----------------------------------------------------------------------
}
