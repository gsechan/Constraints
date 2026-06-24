package com.constraints

/**
 * Marks a [CharSequence] (String, StringBuilder, …) as having a length within [[min], [max]]
 * inclusive: `value.length in min..max`.
 *
 * Proven at compile time for:
 *  - String literals: exact length.
 *  - Concatenation (`+`): sum of both operand lengths when both are statically known.
 *  - Transfer from a value declared with the same or a narrower `@StringLength`.
 *
 * Anything else (dynamic input, string interpolation) requires `checkConstraint(value)`.
 *
 * It may also annotate a return type -- `fun label(): @StringLength(1, 20) String`.
 */
@Constraint(StringLengthValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class StringLength(val min: Int, val max: Int)
