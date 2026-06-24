package com.constraints

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** Direct unit tests of the @Prefix / @Suffix / @Matches validators (no compiler plugin involved). */
class StringMatchingValidatorTest {

    @Test
    fun `prefix accepts a string that starts with the prefix`() {
        PrefixValidator.validate("foobar", Prefix("foo"))
    }

    @Test
    fun `prefix rejects a string that does not start with the prefix`() {
        assertFailsWith<ConstraintException> { PrefixValidator.validate("barfoo", Prefix("foo")) }
    }

    @Test
    fun `suffix accepts a string that ends with the suffix`() {
        SuffixValidator.validate("foobar", Suffix("bar"))
    }

    @Test
    fun `suffix rejects a string that does not end with the suffix`() {
        assertFailsWith<ConstraintException> { SuffixValidator.validate("barfoo", Suffix("bar")) }
    }

    @Test
    fun `matches accepts a value the regex matches in full`() {
        MatchesValidator.validate("abc123", Matches("[a-z]+[0-9]+"))
    }

    @Test
    fun `matches requires the whole value to match`() {
        // A leading character outside the pattern fails -- matches() is anchored to the full input.
        assertFailsWith<ConstraintException> { MatchesValidator.validate("!abc123", Matches("[a-z]+[0-9]+")) }
    }

    @Test
    fun `matches rejects a non-matching value`() {
        assertFailsWith<ConstraintException> { MatchesValidator.validate("hello", Matches("[0-9]+")) }
    }
}
