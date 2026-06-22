package com.constraints.demo

import com.constraints.ByteDivisibleBy
import com.constraints.ConstraintException
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@ByteDivisibleBy` -- the Byte counterpart of `@DivisibleBy`, with the same floored-modulo
 * semantics. Proven at compile time for literals and same-constraint transfers; `checkConstraint`
 * defers to runtime.
 */
class ByteDivisibleByPluginTest {

    @Test
    fun `divisible literal compiles`() {
        @ByteDivisibleBy(2, 0) val a: Byte = 4
        assertEquals(4, a.toInt())
    }

    @Test
    fun `remainder defaults to zero`() {
        @ByteDivisibleBy(3) val a: Byte = 9
        assertEquals(9, a.toInt())
    }

    @Test
    fun `non-zero remainder compiles`() {
        @ByteDivisibleBy(3, 1) val a: Byte = 7 // 7 mod 3 == 1
        assertEquals(7, a.toInt())
    }

    @Test
    fun `transfer between same-divisibility values compiles`() {
        @ByteDivisibleBy(2, 0) val a: Byte = 4
        @ByteDivisibleBy(2, 0) val b: Byte = a
        assertEquals(4, b.toInt())
    }

    @Test
    fun `checkConstraint allows a divisible runtime value`() {
        @ByteDivisibleBy(3, 0) val x: Byte = checkConstraint(9)
        assertEquals(9, x.toInt())
    }

    @Test
    fun `checkConstraint throws for a non-divisible runtime value`() {
        assertFailsWith<ConstraintException> {
            @ByteDivisibleBy(3, 0) val x: Byte = checkConstraint(10) // 10 mod 3 == 1
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @ByteDivisibleBy(2, 0) val x: Byte = 5                   // 5 mod 2 == 1
    //   @ByteDivisibleBy(0, 0) val z: Byte = checkConstraint(4)  // divisor must be non-zero
    // -----------------------------------------------------------------------
}
