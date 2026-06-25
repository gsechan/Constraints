package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Constraints on a collection's element *type argument* -- `List<@IntRange(0, 10) Int>` -- mean
 * "every element satisfies it". The plugin injects per-element validation when assigned via
 * checkConstraint, and proves a transfer from a value with the same element-type constraint.
 */
class GenericTypeAnnotationPluginTest {

    @Test
    fun `checkConstraint passes when every element is in range`() {
        val xs: List<@IntRange(0, 10) Int> = checkConstraint(listOf(1, 5, 9))
        assertEquals(3, xs.size)
    }

    @Test
    fun `checkConstraint throws when an element is out of range`() {
        assertFailsWith<ConstraintException> {
            val xs: List<@IntRange(0, 10) Int> = checkConstraint(listOf(1, 20, 3)) // 20 > 10
            println(xs)
        }
    }

    @Test
    fun `empty collection passes`() {
        val xs: List<@IntRange(0, 10) Int> = checkConstraint(emptyList())
        assertEquals(0, xs.size)
    }

    @Test
    fun `works on a Set too`() {
        assertFailsWith<ConstraintException> {
            val xs: Set<@IntRange(0, 10) Int> = checkConstraint(setOf(2, 11)) // 11 > 10
            println(xs)
        }
    }

    @Test
    fun `transfer between values with the same element-type constraint compiles`() {
        val a: List<@IntRange(0, 10) Int> = checkConstraint(listOf(1, 2))
        val b: List<@IntRange(0, 10) Int> = a   // proven: same element-type constraint
        assertEquals(listOf(1, 2), b)
    }

    // --- Builder of provably-in-range literals: no checkConstraint needed ---

    @Test
    fun `listOf of in-range literals compiles without checkConstraint`() {
        val xs: List<@IntRange(0, 10) Int> = listOf(1, 5, 9)   // every element provably in [0, 10]
        assertEquals(3, xs.size)
    }

    @Test
    fun `empty builder compiles`() {
        val xs: List<@IntRange(0, 10) Int> = listOf()
        assertEquals(0, xs.size)
    }

    @Test
    fun `setOf of in-range literals compiles`() {
        val xs: Set<@IntRange(0, 10) Int> = setOf(2, 4, 6)
        assertEquals(3, xs.size)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   val x: List<@IntRange(0, 10) Int> = listOf(1, 20, 3)  // 20 not in [0,10]: needs checkConstraint
    //   val y: List<@IntRange(0, 10) Int> = someList          // unknown elements: needs checkConstraint
    // -----------------------------------------------------------------------
}
