package com.constraints

/** Alias for `@IntRange(-Int.MAX_VALUE, -1)` -- a strictly-negative Int. */
@IntRange(-Int.MAX_VALUE, -1)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class NegativeInt
