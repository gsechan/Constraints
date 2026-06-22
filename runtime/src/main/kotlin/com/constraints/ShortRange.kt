package com.constraints

/**
 * Marks a Short value as constrained to the inclusive range [[min], [max]] -- the Short counterpart
 * of [IntRange]. Same compile-time proving (literals, narrower `@ShortRange` values, or an explicit
 * `checkConstraint(value)`), clamped to the 16-bit range.
 *
 * It may also annotate a return type -- `fun f(): @ShortRange(0, 5) Short`.
 */
@Constraint(ShortRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS, // so it can be used as an alias meta-annotation
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class ShortRange(val min: Short = Short.MIN_VALUE, val max: Short = Short.MAX_VALUE)
