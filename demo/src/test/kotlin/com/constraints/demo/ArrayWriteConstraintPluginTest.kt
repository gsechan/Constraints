package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.Size
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Returns its argument as an unconstrained Int -- a value the plugin can't prove statically. */
private fun dyn(n: Int): Int = n

/** Return range [2, 5], used to show a write from a constrained source is proven. */
private fun inRange(): @IntRange(2, 5) Int = 3

/**
 * Writing an element into a constrained array (`array[i] = value`) validates the written value
 * against the element-type constraint: proven statically when possible, otherwise via
 * `checkConstraint`.
 */
class ArrayWriteConstraintPluginTest {

    @Test
    fun `writing a provably-in-range literal compiles and runs`() {
        val array: Array<@IntRange(0, 10) Int> = arrayOf(0, 0, 0)
        array[0] = 5   // 5 provably in [0, 10] -> no runtime check
        assertEquals(5, array[0])
    }

    @Test
    fun `writing a value from a constrained source compiles`() {
        val array: Array<@IntRange(0, 10) Int> = arrayOf(0)
        array[0] = inRange()   // @IntRange(2, 5) ⊆ [0, 10]
        assertEquals(3, array[0])
    }

    @Test
    fun `checkConstraint write passes for an in-range value`() {
        val array: Array<@IntRange(0, 10) Int> = arrayOf(0, 0, 0)
        array[1] = checkConstraint(dyn(7))
        assertEquals(7, array[1])
    }

    @Test
    fun `checkConstraint write throws for an out-of-range value`() {
        val array: Array<@IntRange(0, 10) Int> = arrayOf(0, 0, 0)
        assertFailsWith<ConstraintException> {
            array[2] = checkConstraint(dyn(-1))   // -1 not in [0, 10]
        }
    }

    @Test
    fun `explicit set call is validated too`() {
        val array: Array<@IntRange(0, 10) Int> = arrayOf(0)
        assertFailsWith<ConstraintException> {
            array.set(0, checkConstraint(dyn(99)))
        }
    }

    // --- Nested: writing an inner array validates both its size and its elements ---

    @Test
    fun `nested write of provably-valid inner array compiles`() {
        val grid: Array<@Size(1, 2) Array<@IntRange(1, 10) Int>> = checkConstraint(arrayOf(arrayOf(1)))
        grid[0] = arrayOf(5)   // size 1 ∈ [1,2], 5 ∈ [1,10] -> proven
        assertEquals(5, grid[0][0])
    }

    @Test
    fun `nested checkConstraint write throws when an inner element is out of range`() {
        val grid: Array<@Size(1, 2) Array<@IntRange(1, 10) Int>> = checkConstraint(arrayOf(arrayOf(1)))
        assertFailsWith<ConstraintException> {
            grid[0] = checkConstraint(arrayOf(20))   // 20 not in [1, 10]
        }
    }

    @Test
    fun `nested checkConstraint write throws when an inner array is the wrong size`() {
        val grid: Array<@Size(1, 2) Array<@IntRange(1, 10) Int>> = checkConstraint(arrayOf(arrayOf(1)))
        assertFailsWith<ConstraintException> {
            grid[0] = checkConstraint(arrayOf(1, 2, 3))   // size 3 not in [1, 2]
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile (see ArrayWriteCompileFail.kt):
    //   array[1] = -1            // -1 provably out of [0, 10]: HARD error
    //   array[2] = dyn(-1)       // unprovable: needs checkConstraint
    // -----------------------------------------------------------------------
}
