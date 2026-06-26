package com.constraints

/** Alias for `@ArraySize(1, Int.MAX_VALUE)` -- a non-empty array. */
@ArraySize(1, Int.MAX_VALUE)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class NonEmptyArray
