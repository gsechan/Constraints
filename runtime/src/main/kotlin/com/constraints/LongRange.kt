package com.constraints

/**
 * Marks a Long value as constrained to the inclusive range [[min], [max]]. The Long counterpart
 * of [IntRange]: same compile-time proving (literals, narrower `@LongRange` values, interval-safe
 * arithmetic, or an explicit `checkConstraint(value)`), with bounds and overflow handled over the
 * full 64-bit range.
 *
 * It may also annotate a return type -- `fun f(): @LongRange(0, 5) Long`.
 */
@Constraint(LongRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS, // so it can be used as an alias meta-annotation
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class LongRange(val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE)
