package com.constraints

/**
 * Requires a [CharSequence] to end with [suffix] (`value.endsWith(suffix)`).
 *
 * Proven at compile time when assigned a string literal (checked with a cheap `endsWith`, no regex),
 * a value already declared with the identical `@Suffix`, or a concatenation whose right side is
 * suffixed (`anything + <suffixed>`, since prepending can't change the end); a literal that does not
 * end with [suffix] is a compile error. Any other (dynamic) value needs `checkConstraint(value)`.
 * Deliberately *not* built on `@Matches` -- it costs a single `endsWith`, never a regex.
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
