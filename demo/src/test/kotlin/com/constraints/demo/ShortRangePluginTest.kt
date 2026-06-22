package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.ShortRange
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@ShortRange` -- the Short counterpart of `@IntRange`. Proven at compile time for literals and
 * same-range transfers; `checkConstraint(value)` defers to runtime. (Short arithmetic promotes to
 * Int in Kotlin, so arithmetic results need an explicit `.toShort()` and aren't statically proven.)
 */
class ShortRangePluginTest {

    @Test
    fun `in-range literal compiles`() {
        @ShortRange(0, 100) val a: Short = 50
        assertEquals(50, a.toInt())
    }

    @Test
    fun `transfer between same-range values compiles`() {
        @ShortRange(0, 100) val a: Short = 50
        @ShortRange(0, 100) val b: Short = a
        assertEquals(50, b.toInt())
    }

    @Test
    fun `checkConstraint allows an in-range runtime value`() {
        @ShortRange(0, 100) val x: Short = checkConstraint(50)
        assertEquals(50, x.toInt())
    }

    @Test
    fun `checkConstraint throws for an out-of-range runtime value`() {
        assertFailsWith<ConstraintException> {
            @ShortRange(0, 100) val x: Short = checkConstraint(200)
            println(x)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @ShortRange(0, 100) val x: Short = 200        // 200 is out of [0, 100]
    //   @ShortRange(0, 100) val y: Short = someShort  // range unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
