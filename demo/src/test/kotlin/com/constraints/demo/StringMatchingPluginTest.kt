package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.Matches
import com.constraints.Prefix
import com.constraints.Suffix
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@Prefix` / `@Suffix` / `@Matches` are runtime constraints (opaque validators), so the plugin
 * accepts them only via `checkConstraint(value)` or a transfer from a value with the identical
 * constraint -- never a bare literal. The validation runs at runtime when checkConstraint is used.
 */
class StringMatchingPluginTest {

    @Test
    fun `prefix passes for a matching value`() {
        @Prefix("foo") val s = checkConstraint("foobar")
        assertEquals("foobar", s)
    }

    @Test
    fun `prefix throws for a non-matching value`() {
        assertFailsWith<ConstraintException> {
            @Prefix("foo") val s = checkConstraint("barfoo")
            println(s)
        }
    }

    @Test
    fun `suffix passes and throws`() {
        @Suffix("bar") val ok = checkConstraint("foobar")
        assertEquals("foobar", ok)
        assertFailsWith<ConstraintException> {
            @Suffix("bar") val bad = checkConstraint("barfoo")
            println(bad)
        }
    }

    @Test
    fun `matches passes for a full match and throws otherwise`() {
        @Matches("[a-z]+[0-9]+") val ok = checkConstraint("abc123")
        assertEquals("abc123", ok)
        assertFailsWith<ConstraintException> {
            @Matches("[a-z]+[0-9]+") val bad = checkConstraint("123abc")
            println(bad)
        }
    }

    @Test
    fun `transfer between values with the same prefix constraint compiles`() {
        @Prefix("foo") val a = checkConstraint("foobar")
        @Prefix("foo") val b = a   // proven: same annotation + same argument
        assertEquals("foobar", b)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @Prefix("foo") val x = "foobar"          // bare literal: opaque validator, needs checkConstraint
    //   @Prefix("foo") val y = someString         // unknown value: needs checkConstraint
    //   @Prefix("bar") val z = aFooPrefixedValue  // different prefix argument: transfer not proven
    // -----------------------------------------------------------------------
}
