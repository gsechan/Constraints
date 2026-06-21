package com.constraints

/** Runtime validator for [LongRange]; reads `min`/`max` straight off the annotation. */
object LongRangeValidator : Validator<Long, LongRange> {
    override fun validate(value: Long, annotation: LongRange) {
        if (value < annotation.min || value > annotation.max) {
            throw ConstraintException(
                "@LongRange constraint violated: value $value is not within ${annotation.min}..${annotation.max}"
            )
        }
    }
}
