package com.constraints

/**
 * Marks a Byte value as belonging to a residue class: `value.mod(divisor) == remainder` -- the
 * Byte counterpart of [DivisibleBy], with the same floored-modulo semantics (canonical remainder
 * in `[0, divisor)`). `remainder` defaults to 0, so `@ByteDivisibleBy(3)` reads as "divisible by 3".
 */
@Constraint(ByteDivisibleByValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class ByteDivisibleBy(val divisor: Byte, val remainder: Byte = 0)
