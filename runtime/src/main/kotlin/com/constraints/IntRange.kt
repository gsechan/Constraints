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
        throw IllegalStateException("@IntRange constraint violated: expected $min..$max but was $value")
    }
    return value
}
