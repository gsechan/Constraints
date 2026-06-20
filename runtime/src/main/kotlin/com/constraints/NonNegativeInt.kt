package com.constraints

/** Alias for `@IntRange(0, Int.MAX_VALUE)` -- zero or positive. */
@IntRange(0, Int.MAX_VALUE)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class NonNegativeInt
