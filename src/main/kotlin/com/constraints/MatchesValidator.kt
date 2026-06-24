package com.constraints

/**
 * Runtime validator for [Matches]. The entire value must match the pattern ([Regex.matches]). The
 * regex is compiled per call, which keeps the validator stateless (a cache would be shared state and
 * could break the plugin's "same annotation arguments == same constraint" assumption).
 */
object MatchesValidator : Validator<CharSequence, Matches> {
    override fun validate(value: CharSequence, annotation: Matches) {
        if (!annotation.regex.toRegex().matches(value)) {
            throw ConstraintException(
                "@Matches violated: \"$value\" does not match /${annotation.regex}/"
            )
        }
    }
}
