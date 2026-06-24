package com.constraints

/**
 * Requires a [CharSequence] to start with [prefix] (`value.startsWith(prefix)`).
 *
 * Proven at compile time when assigned a string literal (checked with a cheap `startsWith`, no
 * regex) or a value already declared with the identical `@Prefix`; a literal that does not start
 * with [prefix] is a compile error. Any other (dynamic) value needs `checkConstraint(value)`.
 * Deliberately *not* built on `@Matches` -- it costs a single `startsWith`, never a regex.
 *
 * (The parameter is `String` rather than `CharSequence` because annotation arguments can only be
 * `String`; the validated value is still any `CharSequence`.)
 */
@Constraint(PrefixValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class Prefix(val prefix: String)
