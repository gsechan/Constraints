package com.constraints

/**
 * Marks a Double value as constrained to the inclusive range [[min], [max]]. Proven at compile time
 * for literals and same-range transfers; `checkConstraint(value)` defers to runtime. Floating point
 * arithmetic results are not tracked statically (rounding makes sound over-approximation complex),
 * so computed values always need `checkConstraint`.
 *
 * It may also annotate a return type -- `fun f(): @DoubleRange(0.0, 1.0) Double`.
 */
@Constraint(DoubleRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class DoubleRange(val min: Double = Double.NEGATIVE_INFINITY, val max: Double = Double.POSITIVE_INFINITY)
