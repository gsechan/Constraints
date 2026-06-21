package com.constraints

/**
 * Marks a Long value as belonging to a residue class: `value.mod(divisor) == remainder`. The Long
 * counterpart of [DivisibleBy], with the same floored-modulo semantics (canonical remainder in
 * `[0, divisor)`, matching mathematical congruence -- so it differs from `%` for negatives).
 *
 * The compiler plugin proves it at compile time wherever it can determine the value's residue
 * modulo `divisor` (literals, congruence-preserving arithmetic, or a value already declared
 * `@LongDivisibleBy` with a compatible divisor); otherwise wrap the value in `checkConstraint(value)`.
 *
 * `remainder` defaults to 0, so `@LongDivisibleBy(3)` reads as "divisible by 3".
 */
@CompileTimeConstraint(LongDivisibleByValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class LongDivisibleBy(val divisor: Long, val remainder: Long = 0)
