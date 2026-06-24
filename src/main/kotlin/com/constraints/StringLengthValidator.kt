package com.constraints

/** Runtime validator for [StringLength]; checks [CharSequence.length] against the declared bounds. */
object StringLengthValidator : Validator<CharSequence, StringLength> {
    override fun validate(value: CharSequence, annotation: StringLength) {
        val len = value.length
        if (len < annotation.min || len > annotation.max) {
            throw ConstraintException(
                "@StringLength(${annotation.min}, ${annotation.max}) violated: " +
                    "length $len is not within ${annotation.min}..${annotation.max}"
            )
        }
    }
}
