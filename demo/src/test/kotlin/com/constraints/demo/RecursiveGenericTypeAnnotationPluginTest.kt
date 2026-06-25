package com.constraints.demo

import com.constraints.CollectionSize
import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Element-type constraints nest: `List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>>` means every
 * inner list has size in [1, 10] (depth 1) AND every inner element is in [2, 5] (depth 2). The plugin
 * proves builders of provably-valid literals statically, hard-errors a provably-invalid element at any
 * depth, and otherwise injects depth-aware per-element validation behind `checkConstraint`.
 */
class RecursiveGenericTypeAnnotationPluginTest {

    @Test
    fun `builder of provably-valid nested literals compiles without checkConstraint`() {
        // inner list size 1 in [1, 10]; element 3 in [2, 5] -> both depths proven.
        val xs: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> = listOf(listOf(3))
        assertEquals(3, xs[0][0])
    }

    @Test
    fun `builder with several valid inner lists compiles`() {
        val xs: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> = listOf(listOf(2, 5), listOf(3, 4))
        assertEquals(2, xs.size)
    }

    @Test
    fun `checkConstraint passes for valid nested collections`() {
        val xs: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> = checkConstraint(listOf(listOf(2, 3), listOf(4)))
        assertEquals(2, xs.size)
    }

    @Test
    fun `checkConstraint throws when an inner element is out of range (depth 2)`() {
        assertFailsWith<ConstraintException> {
            val xs: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> =
                checkConstraint(listOf(listOf(3, 20))) // 20 not in [2, 5]
            println(xs)
        }
    }

    @Test
    fun `checkConstraint throws when an inner collection violates its size (depth 1)`() {
        assertFailsWith<ConstraintException> {
            val xs: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> =
                checkConstraint(listOf(emptyList())) // inner size 0 not in [1, 10]
            println(xs)
        }
    }

    @Test
    fun `transfer between values with the same nested element-type constraints compiles`() {
        val a: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> = checkConstraint(listOf(listOf(3)))
        val b: List<@CollectionSize(1, 10) List<@IntRange(2, 5) Int>> = a // proven: identical nested constraints
        assertEquals(a, b)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile (see RecursiveGenericTypeCompileFail.kt):
    //   listOf(listOf(20))      // 20 provably out of [2, 5] at depth 2: HARD error
    //   listOf(emptyList())     // inner size 0 provably out of [1, 10] at depth 1: HARD error
    // -----------------------------------------------------------------------
}
