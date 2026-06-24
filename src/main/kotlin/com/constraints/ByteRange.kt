package com.constraints

/**
 * Marks a Byte value as constrained to the inclusive range [[min], [max]] -- the Byte counterpart
 * of [IntRange]. Same compile-time proving (literals, narrower `@ByteRange` values, or an explicit
 * `checkConstraint(value)`), clamped to the 8-bit range.
 *
 * It may also annotate a return type -- `fun f(): @ByteRange(0, 5) Byte`.
 */
@Constraint(ByteRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS, // so it can be used as an alias meta-annotation
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class ByteRange(val min: Byte = Byte.MIN_VALUE, val max: Byte = Byte.MAX_VALUE)
