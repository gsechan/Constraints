package com.constraints.demo

import com.constraints.ByteRange
import com.constraints.ConstraintException
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@ByteRange` -- the Byte counterpart of `@IntRange`. Proven at compile time for literals and
 * same-range transfers; `checkConstraint(value)` defers to runtime. (Byte arithmetic promotes to
 * Int in Kotlin, so arithmetic results need an explicit `.toByte()` and aren't statically proven.)
 */
class ByteRangePluginTest {

    @Test
    fun `in-range literal compiles`() {
        @ByteRange(0, 100) val a: Byte = 50
        assertEquals(50, a.toInt())
    }

    @Test
    fun `transfer between same-range values compiles`() {
        @ByteRange(0, 100) val a: Byte = 50
        @ByteRange(0, 100) val b: Byte = a
        assertEquals(50, b.toInt())
    }

    @Test
    fun `checkConstraint allows an in-range runtime value`() {
        @ByteRange(0, 100) val x: Byte = checkConstraint(50)
        assertEquals(50, x.toInt())
    }

    @Test
    fun `checkConstraint throws for an out-of-range runtime value`() {
        assertFailsWith<ConstraintException> {
            @ByteRange(0, 100) val x: Byte = checkConstraint(120) // 120 fits in a Byte but is > 100
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @ByteRange(0, 100) val x: Byte = 120       // 120 is out of [0, 100]
    //   @ByteRange(0, 100) val y: Byte = someByte  // range unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
