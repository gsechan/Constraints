package com.constraints

/**
 * Requires a [CharSequence] to fully match the regular expression [regex] -- the whole value, not a
 * substring (`regex.toRegex().matches(value)`).
 *
 * Proven at compile time when assigned a string literal (the regex runs against the literal **only**,
 * never against an arbitrary expression) or a value already declared with the identical `@Matches`;
 * a literal that does not match is a compile error. Any other (dynamic) value needs
 * `checkConstraint(value)`, where the regex is compiled and matched at runtime.
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
