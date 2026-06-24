package com.constraints

/**
 * Requires a [CharSequence] to end with [suffix] (`value.endsWith(suffix)`).
 *
 * A runtime constraint: the validator runs arbitrary code, so the plugin can't prove it statically.
 * Assign through `checkConstraint(value)`, or from a value already declared with the identical
 * `@Suffix`. Deliberately *not* built on `@Matches` -- it costs a single `endsWith`, never a regex.
 *
 * (The parameter is `String` rather than `CharSequence` because annotation arguments can only be
 * `String`; the validated value is still any `CharSequence`.)
 */
@Constraint(SuffixValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class Suffix(val suffix: String)
