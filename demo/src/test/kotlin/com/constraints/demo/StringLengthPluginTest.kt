package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.NonEmptyString
import com.constraints.StringLength
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StringLengthPluginTest {

    @Test
    fun `literal whose length is in range compiles`() {
        @StringLength(1, 10) val s = "hello"
        assertEquals("hello", s)
    }

    @Test
    fun `literal at lower boundary compiles`() {
        @StringLength(3, 10) val s = "abc"
        assertEquals("abc", s)
    }

    @Test
    fun `literal at upper boundary compiles`() {
        @StringLength(1, 5) val s = "hello"
        assertEquals("hello", s)
    }

    @Test
    fun `empty string satisfies zero-minimum`() {
        @StringLength(0, 5) val s = ""
        assertEquals("", s)
    }

    @Test
    fun `concatenation of two known-length strings compiles`() {
        @StringLength(2, 5) val a = checkConstraint("hi")
        @StringLength(3, 7) val b = checkConstraint("bye")
        @StringLength(5, 12) val c = a + b   // [2,5]+[3,7] = [5,12]
        assertEquals("hibye", c)
    }

    @Test
    fun `concatenation with a literal compiles`() {
        @StringLength(1, 10) val suffix = checkConstraint("!")
        @StringLength(6, 15) val result = "hello" + suffix  // [5,5]+[1,10] = [6,15]
        assertEquals("hello!", result)
    }

    @Test
    fun `transfer between same-length values compiles`() {
        @StringLength(1, 10) val a = "hello"
        @StringLength(1, 10) val b = a
        assertEquals("hello", b)
    }

    @Test
    fun `transfer from narrower range compiles`() {
        @StringLength(3, 5) val a = checkConstraint("hi!")
        @StringLength(1, 10) val b = a   // [3,5] ⊆ [1,10]
        assertEquals("hi!", b)
    }

    @Test
    fun `checkConstraint passes for string in range`() {
        @StringLength(1, 10) val s = checkConstraint("hello")
        assertEquals("hello", s)
    }

    @Test
    fun `checkConstraint throws for string that is too long`() {
        assertFailsWith<ConstraintException> {
            @StringLength(1, 3) val s = checkConstraint("hello")
            println(s)
        }
    }

    @Test
    fun `checkConstraint throws for empty string when min is one`() {
        assertFailsWith<ConstraintException> {
            @StringLength(1, 10) val s = checkConstraint("")
            println(s)
        }
    }

    // --- @NonEmptyString alias ---

    @Test
    fun `NonEmptyString alias accepts a non-empty literal`() {
        @NonEmptyString val s = "hello"
        assertEquals("hello", s)
    }

    @Test
    fun `NonEmptyString checkConstraint throws for empty string`() {
        assertFailsWith<ConstraintException> {
            @NonEmptyString val s = checkConstraint("")
            println(s)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @StringLength(1, 3) val x = "hello"       // length 5 not in [1, 3]
    //   @StringLength(1, 5) val y = userInput     // length unknown: needs checkConstraint
    //   @NonEmptyString val z = ""               // length 0 not in [1, MAX_VALUE]
    // -----------------------------------------------------------------------
}
