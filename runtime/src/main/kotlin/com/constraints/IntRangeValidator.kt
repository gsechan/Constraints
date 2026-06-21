package com.constraints

/** Runtime validator for [IntRange]; reads `min`/`max` straight off the annotation. */
object IntRangeValidator : ConstraintValidator<Int, IntRange> {
    override fun validate(value: Int, annotation: IntRange) {
        if (value < annotation.min || value > annotation.max) {
            throw ConstraintException(
                "@IntRange constraint violated: value $value is not within ${annotation.min}..${annotation.max}"
            )
        }
    }
}
