package com.constraints

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for the pure runtime validator behind `@DivisibleBy`. These exercise the
 * validator directly (the annotation is instantiated as an ordinary object) -- no compiler
 * plugin involved. The validator uses floored modulo, so it must agree with the plugin's
 * compile-time congruence check for the constraint to be sound.
 */
class DivisibleByValidatorTest {

    @Test
    fun `accepts a value with the matching remainder`() {
        DivisibleByValidator.validate(9, DivisibleBy(3, 0)) // 9 mod 3 == 0
        DivisibleByValidator.validate(7, DivisibleBy(3, 1)) // 7 mod 3 == 1
    }

    @Test
    fun `rejects a value with the wrong remainder`() {
        assertFailsWith<ConstraintException> { DivisibleByValidator.validate(10, DivisibleBy(3, 0)) } // 10 mod 3 == 1
        assertFailsWith<ConstraintException> { DivisibleByValidator.validate(8, DivisibleBy(3, 1)) }  // 8 mod 3 == 2
    }

    @Test
    fun `uses floored modulo for negative values`() {
        // -1 is congruent to 2 modulo 3 (floored), not -1 as Kotlin's `%` would give.
        DivisibleByValidator.validate(-1, DivisibleBy(3, 2))
        DivisibleByValidator.validate(-3, DivisibleBy(3, 0))
        assertFailsWith<ConstraintException> { DivisibleByValidator.validate(-1, DivisibleBy(3, 0)) }
    }

    @Test
    fun `normalizes the remainder modulo the divisor`() {
        // remainder 4 normalizes to 1 (mod 3); 7 mod 3 == 1, so this is accepted.
        DivisibleByValidator.validate(7, DivisibleBy(3, 4))
    }

    @Test
    fun `accepts every value when divisor is one`() {
        DivisibleByValidator.validate(0, DivisibleBy(1, 0))
        DivisibleByValidator.validate(42, DivisibleBy(1, 0))
        DivisibleByValidator.validate(-7, DivisibleBy(1, 0))
    }

    @Test
    fun `rejects a zero divisor`() {
        assertFailsWith<ConstraintException> { DivisibleByValidator.validate(5, DivisibleBy(0, 0)) }
    }
}