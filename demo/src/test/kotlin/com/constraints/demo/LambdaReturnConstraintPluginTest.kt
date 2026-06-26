package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A class with a hand-annotated function-type return -- the constraint is written directly. */
class IntBox(val produce: () -> @IntRange(0, 10) Int)

/**
 * A lambda whose expected return type carries a constraint has its returned value validated:
 *  - the `Array(size) { init }` constructor, where the return type is the array element type
 *    (`Array<@IntRange Int>`, constraint via type-argument substitution), and
 *  - a hand-annotated `() -> @IntRange Int` parameter (constraint written directly).
 */
class LambdaReturnConstraintPluginTest {

    // === Array(size) { init } constructor ===

    @Test
    fun `Array constructor with a provable init compiles`() {
        val a: Array<@IntRange(0, 10) Int> = Array(5) { 0 }   // every element provably 0 ∈ [0, 10]
        assertEquals(5, a.size)
    }

    @Test
    fun `Array constructor with a provable constant init compiles`() {
        val a: Array<@IntRange(0, 10) Int> = Array(3) { 7 }
        assertEquals(7, a[0])
    }

    @Test
    fun `unprovable Array init compiles via checkConstraint and passes when values are in range`() {
        val a: Array<@IntRange(0, 10) Int> = checkConstraint(Array(5) { it % 11 })  // 0..4, all in range
        assertEquals(5, a.size)
    }

    @Test
    fun `checkConstraint Array constructor throws when init produces an out-of-range value`() {
        assertFailsWith<ConstraintException> {
            val a: Array<@IntRange(0, 10) Int> = checkConstraint(Array(5) { it + 20 })  // 20.. out of range
            println(a)
        }
    }

    // === Hand-annotated () -> @IntRange Int parameter ===

    @Test
    fun `lambda with a provable return compiles`() {
        val box = IntBox { 5 }
        assertEquals(5, box.produce())
    }

    @Test
    fun `lambda with a provable constant return at the boundary compiles`() {
        val box = IntBox { 10 }
        assertEquals(10, box.produce())
    }

    // -----------------------------------------------------------------------
    // These do NOT compile (see LambdaReturnCompileFail.kt):
    //   Array<@IntRange(0, 10) Int> = Array(5) { 100 }   // 100 provably out of range: HARD error
    //   Array<@IntRange(0, 10) Int> = Array(5) { it }    // unprovable: needs checkConstraint(Array(...))
    //   IntBox { 100 }                                   // 100 provably out of range: HARD error
    // -----------------------------------------------------------------------
}
