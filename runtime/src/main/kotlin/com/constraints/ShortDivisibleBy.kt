package com.constraints

/**
 * Marks a Short value as belonging to a residue class: `value.mod(divisor) == remainder` -- the
 * Short counterpart of [DivisibleBy], with the same floored-modulo semantics (canonical remainder
 * in `[0, divisor)`). `remainder` defaults to 0, so `@ShortDivisibleBy(3)` reads as "divisible by 3".
 */
@Constraint(ShortDivisibleByValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class ShortDivisibleBy(val divisor: Short, val remainder: Short = 0)
