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
 * `@Prefix` / `@Suffix` / `@Matches` are proven at compile time for string literals (and same-constraint
 * transfers). `@Prefix`/`@Suffix` use startsWith/endsWith; `@Matches` runs the regex -- but only on a
 * literal, never on an arbitrary expression. Dynamic values still defer to `checkConstraint`.
 */
class StringMatchingPluginTest {

    // --- Literals proven at compile time ---

    @Test
    fun `prefix literal that matches compiles`() {
        @Prefix("foo") val a = "foobar"
        assertEquals("foobar", a)
    }

    @Test
    fun `suffix literal that matches compiles`() {
        @Suffix("bar") val a = "foobar"
        assertEquals("foobar", a)
    }

    @Test
    fun `matches literal that matches compiles`() {
        @Matches("[a-z]+[0-9]+") val a = "abc123"
        assertEquals("abc123", a)
    }

    @Test
    fun `multiple string constraints on one literal compile`() {
        @Prefix("foo") @Suffix("bar") val a = "foobazbar"
        assertEquals("foobazbar", a)
    }

    // --- Transfer ---

    @Test
    fun `transfer between values with the same prefix constraint compiles`() {
        @Prefix("foo") val a = "foobar"
        @Prefix("foo") val b = a   // proven: same annotation + same argument
        assertEquals("foobar", b)
    }

    // --- @Prefix is closed under appending ---

    @Test
    fun `appending to a prefixed value preserves the prefix`() {
        @Prefix("foo") val a = "foome"
        @Prefix("foo") val b = a + " and me"   // a starts with "foo", so a + anything does too
        assertEquals("foome and me", b)
    }

    @Test
    fun `literal prefix concatenated with a dynamic tail compiles`() {
        val tail = "anything"
        @Prefix("foo") val b = "foo" + tail    // left literal "foo" carries the prefix
        assertEquals("fooanything", b)
    }

    @Test
    fun `chained appends keep the prefix`() {
        @Prefix("foo") val a = "foo!"
        @Prefix("foo") val b = a + "x" + "y"   // ((a + "x") + "y"); leftmost is a
        assertEquals("foo!xy", b)
    }

    // --- @Suffix is closed under prepending (the dual) ---

    @Test
    fun `prepending to a suffixed value preserves the suffix`() {
        @Suffix("bar") val a = "mebar"
        @Suffix("bar") val b = "and me " + a   // a ends with "bar", so anything + a does too
        assertEquals("and me mebar", b)
    }

    @Test
    fun `dynamic head concatenated with a literal suffix compiles`() {
        val head = "anything"
        @Suffix("bar") val b = head + "bar"    // right literal "bar" carries the suffix
        assertEquals("anythingbar", b)
    }

    // --- Dynamic values via checkConstraint ---

    @Test
    fun `checkConstraint passes for a matching runtime value`() {
        val input = "foobar"
        @Prefix("foo") val a = checkConstraint(input)
        assertEquals("foobar", a)
    }

    @Test
    fun `checkConstraint throws for a non-matching runtime value`() {
        val input = "barfoo"
        assertFailsWith<ConstraintException> {
            @Prefix("foo") val a = checkConstraint(input)
            println(a)
        }
    }

    @Test
    fun `matches checkConstraint throws for a non-matching runtime value`() {
        val input = "123abc"
        assertFailsWith<ConstraintException> {
            @Matches("[a-z]+[0-9]+") val a = checkConstraint(input)
            println(a)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @Prefix("foo") val x = "barfoo"            // literal doesn't start with "foo": can never be valid
    //   @Suffix("bar") val y = "barfoo"            // literal doesn't end with "bar": can never be valid
    //   @Matches("[0-9]+") val z = "abc"           // literal doesn't match: can never be valid
    //   @Prefix("foo") val w = someString          // not a literal/known value: needs checkConstraint
    //   @Prefix("bar") val v = aFooPrefixedValue   // different prefix argument: transfer not proven
    // -----------------------------------------------------------------------
}
