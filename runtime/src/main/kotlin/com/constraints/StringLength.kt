package com.constraints

/**
 * Marks a [CharSequence] (String, StringBuilder, …) as having a length within [[min], [max]]
 * inclusive: `value.length in min..max`.
 *
 * The compiler plugin proves this at compile time for:
 *  - String literals (`"hello"` has a known length of 5).
 *  - Concatenation of two `@StringLength`-annotated values: the result length is the sum of the
 *    two length ranges, so if `a` is `@StringLength(2, 5)` and `b` is `@StringLength(3, 7)` then
 *    `a + b` is provably in `[5, 12]`.
 *  - Transfer from another value declared with the same or a narrower `@StringLength`.
 *
 * Any other expression (dynamic input, other function calls, string interpolation) cannot be
 * proven statically and requires `checkConstraint(value)`.
 *
 * It may also annotate a return type -- `fun f(): @StringLength(1, 10) String`.
 */
@Constraint(StringLengthValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS, // so it can be used as an alias meta-annotation
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class StringLength(val min: Int, val max: Int)
