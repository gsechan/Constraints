package com.constraints

/**
 * Marks an Int value as constrained to the inclusive range [[min], [max]].
 *
 * The compiler plugin checks this at COMPILE TIME: an assignment is allowed only
 * if the value is provably within `[min, max]` (an in-range literal, a narrower
 * @IntRange value, interval-safe arithmetic, or an explicit [checkIntRange]
 * call). Otherwise it is a compile error.
 *
 * It may also annotate a return type -- `fun f(): @IntRange(0, 5) Int` -- in
 * which case callers may trust the result without a runtime check, and the
 * function's `return`s are themselves checked against the range.
 */
@CompileTimeConstraint(IntRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class IntRange(val min: Int, val max: Int)

/**
 * Runtime guard injected by the compiler plugin. Returns [value] unchanged when
 * it lies within `[min, max]` inclusive, otherwise throws.
 */
fun checkIntRange(value: Int, min: Int, max: Int): Int {
    if (value < min || value > max) {
        throw ConstraintException("@IntRange constraint violated: value $value is not within $min..$max")
    }
    return value
}

/**
 * Single-argument escape hatch. The compiler plugin fills in `min`/`max` from the
 * `@IntRange` of the value being assigned to, rewriting this call to the 3-arg
 * form. It therefore only works as the direct initializer/assignment of an
 * `@IntRange` value; anywhere else the plugin can't supply bounds and it throws.
 */
@Suppress("UNUSED_PARAMETER")
fun checkIntRange(value: Int): Int =
    throw ConstraintException(
        "checkIntRange(value) must initialise an @IntRange value so the compiler can supply its bounds"
    )
