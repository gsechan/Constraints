package com.constraints

/**
 * Requires a [CharSequence] to fully match the regular expression [regex] -- the whole value, not a
 * substring (`regex.toRegex().matches(value)`).
 *
 * A runtime constraint: the regex is compiled and matched at runtime, **never in the compiler**.
 * Assign through `checkConstraint(value)`, or from a value already declared with the identical
 * `@Matches`.
 *
 * (The parameter is `String` rather than `CharSequence` because annotation arguments can only be
 * `String`; the validated value is still any `CharSequence`.)
 */
@Constraint(MatchesValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class Matches(val regex: String)
