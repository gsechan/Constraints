package com.constraints.demo

import com.constraints.ArraySize
import com.constraints.ConstraintException
import com.constraints.NonEmptyArray
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @ArraySize works like @CollectionSize but for arrays -- both Array<T> and the primitive array
 * aliases (IntArray, DoubleArray, ...).
 */
class ArraySizePluginTest {

    // --- Object-array factory functions ---

    @Test
    fun `emptyArray has size zero`() {
        @ArraySize(0, 0) val arr = emptyArray<String>()
        assertEquals(0, arr.size)
    }

    @Test
    fun `arrayOf with known args compiles`() {
        @ArraySize(3, 3) val arr = arrayOf("a", "b", "c")
        assertEquals(3, arr.size)
    }

    @Test
    fun `single-element arrayOf compiles`() {
        @ArraySize(1, 1) val arr = arrayOf("only")
        assertEquals(1, arr.size)
    }

    @Test
    fun `arrayOfNulls with a literal size compiles`() {
        @ArraySize(4, 4) val arr = arrayOfNulls<Int>(4)
        assertEquals(4, arr.size)
    }

    // --- Primitive array aliases ---

    @Test
    fun `intArrayOf with known args compiles`() {
        @ArraySize(3, 3) val arr = intArrayOf(1, 2, 3)
        assertEquals(3, arr.size)
    }

    @Test
    fun `doubleArrayOf with known args compiles`() {
        @ArraySize(2, 2) val arr = doubleArrayOf(1.0, 2.0)
        assertEquals(2, arr.size)
    }

    @Test
    fun `booleanArrayOf with known args compiles`() {
        @ArraySize(1, 5) val arr = booleanArrayOf(true, false)
        assertEquals(2, arr.size)
    }

    // --- Addition ---

    @Test
    fun `adding a single element increments size by exactly one`() {
        @ArraySize(3, 3) val arr = intArrayOf(1, 2, 3)
        @ArraySize(4, 4) val grown = arr + 4   // single element → always +1, proven exactly
        assertEquals(4, grown.size)
    }

    @Test
    fun `adding two known-size arrays compiles`() {
        @ArraySize(2, 2) val a = intArrayOf(1, 2)
        @ArraySize(3, 3) val b = intArrayOf(3, 4, 5)
        @ArraySize(5, 5) val c = a + b   // [2,2]+[3,3] = [5,5]
        assertEquals(5, c.size)
    }

    @Test
    fun `adding to a bounded array compiles`() {
        @ArraySize(1, 5) val arr = checkConstraint(intArrayOf(1, 2))
        @ArraySize(2, 6) val grown = arr + 3   // [1,5] + 1 = [2,6]
        assertEquals(3, grown.size)
    }

    // --- Transfer ---

    @Test
    fun `transfer between same-size arrays compiles`() {
        @ArraySize(3, 3) val a = arrayOf(1, 2, 3)
        @ArraySize(3, 3) val b = a
        assertEquals(3, b.size)
    }

    @Test
    fun `transfer from narrower size compiles`() {
        @ArraySize(3, 3) val a = intArrayOf(1, 2, 3)
        @ArraySize(1, 5) val b = a   // [3,3] ⊆ [1,5]
        assertEquals(3, b.size)
    }

    // --- Runtime escape hatch ---

    @Test
    fun `checkConstraint passes for array in range`() {
        @ArraySize(1, 5) val arr = checkConstraint(arrayOf("a", "b"))
        assertEquals(2, arr.size)
    }

    @Test
    fun `checkConstraint throws for array that is too large`() {
        assertFailsWith<ConstraintException> {
            @ArraySize(1, 2) val arr = checkConstraint(intArrayOf(1, 2, 3, 4))
            println(arr)
        }
    }

    @Test
    fun `checkConstraint throws for empty array when min is one`() {
        assertFailsWith<ConstraintException> {
            @ArraySize(1, 10) val arr = checkConstraint(emptyArray<String>())
            println(arr)
        }
    }

    // --- @NonEmptyArray alias ---

    @Test
    fun `NonEmptyArray alias accepts a non-empty factory result`() {
        @NonEmptyArray val arr = intArrayOf(1, 2, 3)
        assertEquals(3, arr.size)
    }

    @Test
    fun `NonEmptyArray checkConstraint throws for empty array`() {
        assertFailsWith<ConstraintException> {
            @NonEmptyArray val arr = checkConstraint(intArrayOf())
            println(arr)
        }
    }

    // --- As an element-type constraint: List<@ArraySize IntArray> ---

    @Test
    fun `checkConstraint validates @ArraySize on list elements`() {
        val rows: List<@ArraySize(2, 2) IntArray> = checkConstraint(listOf(intArrayOf(1, 2), intArrayOf(3, 4)))
        assertEquals(2, rows.size)
    }

    @Test
    fun `checkConstraint throws when a list element array has the wrong size`() {
        assertFailsWith<ConstraintException> {
            val rows: List<@ArraySize(2, 2) IntArray> = checkConstraint(listOf(intArrayOf(1, 2), intArrayOf(3)))
            println(rows)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile (see ArraySizeCompileFail.kt):
    //   @ArraySize(5, 5) val x = intArrayOf(1, 2)    // size 2 not in [5, 5]
    //   @ArraySize(1, 5) val y = someArray           // size unknown: needs checkConstraint
    //   @NonEmptyArray  val w = emptyArray<Int>()    // size 0 not in [1, MAX_VALUE]
    // -----------------------------------------------------------------------
}
