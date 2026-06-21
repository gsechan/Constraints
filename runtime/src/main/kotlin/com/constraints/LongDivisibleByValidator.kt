package com.constraints

/**
 * Runtime validator for [LongDivisibleBy]. Uses floored modulo ([Long.mod]) so it agrees exactly
 * with the compiler plugin's compile-time congruence check.
 */
object LongDivisibleByValidator : ConstraintValidator<Long, LongDivisibleBy> {
    override fun validate(value: Long, annotation: LongDivisibleBy) {
        if (annotation.divisor == 0L) {
            throw ConstraintException("@LongDivisibleBy divisor must be non-zero")
        }
        val actual = value.mod(annotation.divisor)
        val expected = annotation.remainder.mod(annotation.divisor)
        if (actual != expected) {
            throw ConstraintException(
                "@LongDivisibleBy(${annotation.divisor}, ${annotation.remainder}) violated: " +
                    "$value mod ${annotation.divisor} = $actual, expected $expected"
            )
        }
    }
}
