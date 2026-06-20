package com.constraints

/**
 * Runtime validator for [DivisibleBy]. Uses floored modulo ([Int.mod]) so it agrees exactly
 * with the compiler plugin's compile-time congruence check.
 */
object DivisibleByValidator : ConstraintValidator<DivisibleBy> {
    override fun validate(value: Int, annotation: DivisibleBy) {
        if (annotation.divisor == 0) {
            throw ConstraintException("@DivisibleBy divisor must be non-zero")
        }
        val actual = value.mod(annotation.divisor)
        val expected = annotation.remainder.mod(annotation.divisor)
        if (actual != expected) {
            throw ConstraintException(
                "@DivisibleBy(${annotation.divisor}, ${annotation.remainder}) violated: " +
                    "$value mod ${annotation.divisor} = $actual, expected $expected"
            )
        }
    }
}
