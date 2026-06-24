package com.constraints

/**
 * Marks a Float value as constrained to the inclusive range [[min], [max]]. Proven at compile time
 * for literals and same-range transfers; `checkConstraint(value)` defers to runtime. Floating point
 * arithmetic results are not tracked statically (rounding makes sound over-approximation complex),
 * so computed values always need `checkConstraint`.
 *
 * It may also annotate a return type -- `fun f(): @FloatRange(0.0f, 1.0f) Float`.
 */
@Constraint(FloatRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class FloatRange(val min: Float = Float.NEGATIVE_INFINITY, val max: Float = Float.POSITIVE_INFINITY)
