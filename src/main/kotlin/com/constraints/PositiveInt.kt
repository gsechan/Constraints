package com.constraints

/**
 * Alias for `@IntRange(1, Int.MAX_VALUE)` -- a strictly-positive Int.
 *
 * It behaves exactly like `@IntRange` (compile-time proving + the `checkConstraint`
 * runtime escape hatch) because the plugin follows the `@IntRange` meta-annotation on
 * this declaration. Defining an alias needs no plugin changes -- just meta-annotate it
 * with the constraint it stands for.
 */
@IntRange(1, Int.MAX_VALUE)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class PositiveInt

