package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.StringLength
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@StringLength(min, max)` -- checks that a CharSequence's length is in [min, max]. Proven at
 * compile time for string literals, concatenation of known-length values, and transfers from
 * same-or-narrower @StringLength values. Anything else requires `checkConstraint(value)`.
 */
class StringLengthPluginTest {

    // --- Literal proving ---

    @Test
    fun `literal whose length is in range compiles`() {
        @StringLength(1, 10) val s = "hello"   // length 5, in [1, 10]
        assertEquals("hello", s)
    }

    @Test
    fun `literal at the lower boundary compiles`() {
        @StringLength(3, 10) val s = "abc"     // length 3 == min
        assertEquals("abc", s)
    }

    @Test
    fun `literal at the upper boundary compiles`() {
        @StringLength(1, 5) val s = "hello"    // length 5 == max
        assertEquals("hello", s)
    }

    @Test
    fun `empty string satisfies zero-minimum`() {
        @StringLength(0, 5) val s = ""
        assertEquals("", s)
    }

    // --- Concatenation proving ---

    @Test
    fun `concatenation of two known-length strings compiles`() {
        // [2, 5] + [3, 7] = [5, 12], which is a subset of [5, 12]
        @StringLength(2, 5) val a = checkConstraint("hi")
        @StringLength(3, 7) val b = checkConstraint("bye")
        @StringLength(5, 12) val c = a + b
        assertEquals("hibye", c)
    }

    @Test
    fun `concatenation with a literal compiles`() {
        // "hello" has length 5; [5, 5] + [1, 10] = [6, 15] -- subset of [1, 20]
        @StringLength(1, 10) val suffix = checkConstraint("!")
        @StringLength(1, 20) val result = "hello" + suffix
        assertEquals("hello!", result)
    }

    // --- Transfer ---

    @Test
    fun `transfer between same-length values compiles`() {
        @StringLength(1, 10) val a = "hello"
        @StringLength(1, 10) val b = a
        assertEquals("hello", b)
    }

    @Test
    fun `transfer from narrower range to wider compiles`() {
        @StringLength(3, 5) val a = checkConstraint("hi!")
        @StringLength(1, 10) val b = a   // [3, 5] ⊆ [1, 10]
        assertEquals("hi!", b)
    }

    // --- Runtime escape hatch ---

    @Test
    fun `checkConstraint passes for a string in range`() {
        @StringLength(1, 10) val s = checkConstraint("hello")
        assertEquals("hello", s)
    }

    @Test
    fun `checkConstraint throws for a string that is too long`() {
        assertFailsWith<ConstraintException> {
            @StringLength(1, 3) val s = checkConstraint("hello")
            println(s)
        }
    }

    @Test
    fun `checkConstraint throws for an empty string when min is one`() {
        assertFailsWith<ConstraintException> {
            @StringLength(1, 10) val s = checkConstraint("")
            println(s)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @StringLength(1, 3) val x = "hello"        // length 5 not in [1, 3]
    //   @StringLength(5, 10) val y = ""            // length 0 not in [5, 10]
    //   @StringLength(1, 5) val z = userInput      // length unknown: needs checkConstraint
    // -----------------------------------------------------------------------
}
