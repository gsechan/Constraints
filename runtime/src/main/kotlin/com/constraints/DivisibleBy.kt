package com.constraints

/**
 * Marks an Int value as belonging to a residue class: `value.mod(divisor) == remainder`.
 *
 * Modulo is *floored* (Kotlin's [Int.mod]) -- the canonical remainder in `[0, divisor)` --
 * so the constraint expresses true mathematical congruence and composes through arithmetic.
 * This differs from `%` only for negative values: `-1` satisfies `@DivisibleBy(3, 2)` because
 * `-1` is congruent to `2` modulo `3`, even though `-1 % 3 == -1`.
 *
 * The compiler plugin checks this at COMPILE TIME wherever it can determine the value's
 * residue modulo `divisor`: an integer literal, a value already declared `@DivisibleBy` with a
 * compatible divisor, or congruence-preserving arithmetic (`+`, `-`, `*`, `++`, `--`, unary
 * minus). Otherwise it is a compile error -- wrap the value in `checkConstraint(value)` to
 * defer the check to runtime.
 *
 * `remainder` defaults to 0, so `@DivisibleBy(3)` reads as "divisible by 3".
 */
@CompileTimeConstraint(DivisibleByValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS, // so it can be used as an alias meta-annotation
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class DivisibleBy(val divisor: Int, val remainder: Int = 0)
