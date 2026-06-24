package com.constraints

/**
 * Requires a [CharSequence] to start with [prefix] (`value.startsWith(prefix)`).
 *
 * A runtime constraint: the validator runs arbitrary code, so the plugin can't prove it statically.
 * Assign through `checkConstraint(value)`, or from a value already declared with the identical
 * `@Prefix`. Deliberately *not* built on `@Matches` -- it costs a single `startsWith`, never a regex.
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
