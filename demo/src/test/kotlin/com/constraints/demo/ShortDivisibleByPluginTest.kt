package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.ShortDivisibleBy
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@ShortDivisibleBy` -- the Short counterpart of `@DivisibleBy`, with the same floored-modulo
 * semantics. Proven at compile time for literals and same-constraint transfers; `checkConstraint`
 * defers to runtime.
 */
class ShortDivisibleByPluginTest {

    @Test
    fun `divisible literal compiles`() {
        @ShortDivisibleBy(2, 0) val a: Short = 4
        assertEquals(4, a.toInt())
    }

    @Test
    fun `remainder defaults to zero`() {
        @ShortDivisibleBy(3) val a: Short = 9
        assertEquals(9, a.toInt())
    }

    @Test
    fun `non-zero remainder compiles`() {
        @ShortDivisibleBy(3, 1) val a: Short = 7 // 7 mod 3 == 1
        assertEquals(7, a.toInt())
    }

    @Test
    fun `transfer between same-divisibility values compiles`() {
        @ShortDivisibleBy(2, 0) val a: Short = 4
        @ShortDivisibleBy(2, 0) val b: Short = a
        assertEquals(4, b.toInt())
    }

    @Test
    fun `checkConstraint allows a divisible runtime value`() {
        @ShortDivisibleBy(3, 0) val x: Short = checkConstraint(9)
        assertEquals(9, x.toInt())
    }

    @Test
    fun `checkConstraint throws for a non-divisible runtime value`() {
        assertFailsWith<ConstraintException> {
            @ShortDivisibleBy(3, 0) val x: Short = checkConstraint(10) // 10 mod 3 == 1
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @ShortDivisibleBy(2, 0) val x: Short = 5                   // 5 mod 2 == 1
    //   @ShortDivisibleBy(0, 0) val z: Short = checkConstraint(4)  // divisor must be non-zero
    // -----------------------------------------------------------------------
}
