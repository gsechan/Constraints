package com.constraints

/** Runtime validator for [Prefix]; checks [CharSequence.startsWith] (no regex). */
object PrefixValidator : Validator<CharSequence, Prefix> {
    override fun validate(value: CharSequence, annotation: Prefix) {
        if (!value.startsWith(annotation.prefix)) {
            throw ConstraintException(
                "@Prefix violated: \"$value\" does not start with \"${annotation.prefix}\""
            )
        }
    }
}
