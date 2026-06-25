package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.Prefix
import com.constraints.Suffix
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * String-matching constraints (@Prefix / @Suffix / @Matches) work as element-type constraints too --
 * `List<@Prefix("foo") String>` means "every element starts with foo". Literal builders are proven
 * statically, a provably-violating literal is a hard error, and checkConstraint injects per-element
 * validation. (This closes the standing rule that every constraint works inside `List<...>`.)
 */
class StringMatchElementConstraintTest {

    @Test
    fun `builder of literals that all match the prefix compiles`() {
        val xs: List<@Prefix("foo") String> = listOf("foobar", "foobaz") // every literal starts with foo
        assertEquals(2, xs.size)
    }

    @Test
    fun `builder of literals that all match the suffix compiles`() {
        val xs: List<@Suffix("bar") String> = listOf("foobar", "bazbar")
        assertEquals(2, xs.size)
    }

    @Test
    fun `checkConstraint passes when every element matches`() {
        val xs: List<@Prefix("foo") String> = checkConstraint(listOf("foo1", "foo2"))
        assertEquals(2, xs.size)
    }

    @Test
    fun `checkConstraint throws when an element does not match the prefix`() {
        assertFailsWith<ConstraintException> {
            val xs: List<@Prefix("foo") String> = checkConstraint(listOf("foo1", "nope"))
            println(xs)
        }
    }

    @Test
    fun `transfer between values with the same element-type prefix constraint compiles`() {
        val a: List<@Prefix("foo") String> = listOf("foox")
        val b: List<@Prefix("foo") String> = a
        assertEquals(a, b)
    }

    // -----------------------------------------------------------------------
    // Does NOT compile (see StringMatchElementCompileFail.kt):
    //   listOf("foobar", "nope")   // "nope" provably lacks prefix "foo": HARD error
    // -----------------------------------------------------------------------
}
