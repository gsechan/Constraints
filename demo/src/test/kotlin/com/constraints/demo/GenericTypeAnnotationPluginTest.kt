package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.DivisibleBy
import com.constraints.IntRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A function whose return range [2, 5] fits inside the element constraints exercised below. */
private fun smallPositive(): @IntRange(2, 5) Int = 3

/** Return range [3, 4] -- used to show `foo() + 1` (-> [4, 5]) is inferred for an element. */
private fun threeOrFour(): @IntRange(3, 4) Int = 3

/** Constrained-return divisibility, to show element inference reads a function's @DivisibleBy. */
private fun even(): @DivisibleBy(2, 0) Int = 4

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

    @Test
    fun `element from a function with a fitting return range is proven`() {
        // smallPositive() returns @IntRange(2, 5), which is a subset of [0, 10].
        val xs: List<@IntRange(0, 10) Int> = listOf(smallPositive())
        assertEquals(3, xs[0])
    }

    @Test
    fun `mixed literals and a constrained call, all provably in range`() {
        val xs: List<@IntRange(0, 10) Int> = listOf(1, smallPositive(), 9)
        assertEquals(3, xs.size)
    }

    @Test
    fun `arithmetic on a constrained call element is inferred`() {
        // threeOrFour() is @IntRange(3, 4); + 1 gives [4, 5], a subset of [1, 10].
        val xs: List<@IntRange(1, 10) Int> = listOf(threeOrFour() + 1)
        assertEquals(1, xs.size)
    }

    @Test
    fun `divisibility of a constrained call element is inferred`() {
        // even() is @DivisibleBy(2, 0); +2 stays even, so both elements satisfy @DivisibleBy(2, 0).
        val xs: List<@DivisibleBy(2, 0) Int> = listOf(even(), even() + 2)
        assertEquals(2, xs.size)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   val x: List<@IntRange(0, 10) Int> = listOf(1, 20, 3)   // 20 provably out of [0,10]: HARD error
    //   val y: List<@IntRange(0, 10) Int> = listOf(1, dynamic) // a dynamic element: needs checkConstraint
    //   val z: List<@IntRange(0, 10) Int> = someList           // unknown collection: needs checkConstraint
    // -----------------------------------------------------------------------
}
