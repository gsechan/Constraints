package com.constraints

/**
 * Marks an Int value as constrained to the inclusive range [[min], [max]].
 *
 * The compiler plugin injects a runtime check at every assignment to a value
 * annotated with [IntRange]: the assigned value must satisfy
 * `min <= value <= max`, otherwise an [IllegalStateException] is thrown.
 *
 * This is the runtime-enforcement baseline of "Constrained Value Programming".
 * A later layer adds *static* (compile-time) checking so provably-valid
 * assignments need no runtime check at all.
 */
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
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
