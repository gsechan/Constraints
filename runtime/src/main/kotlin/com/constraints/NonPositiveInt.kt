package com.constraints

/** Alias for `@IntRange(-Int.MAX_VALUE, 0)` -- zero or negative. */
@IntRange(-Int.MAX_VALUE, 0)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class NonPositiveInt
