package com.constraints

/** Runtime validator for [Suffix]; checks [CharSequence.endsWith] (no regex). */
object SuffixValidator : Validator<CharSequence, Suffix> {
    override fun validate(value: CharSequence, annotation: Suffix) {
        if (!value.endsWith(annotation.suffix)) {
            throw ConstraintException(
                "@Suffix violated: \"$value\" does not end with \"${annotation.suffix}\""
            )
        }
    }
}
