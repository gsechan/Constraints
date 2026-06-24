package com.constraints

/** Alias for `@DivisibleBy(2, 1)` -- an odd Int. */
@DivisibleBy(2, 1)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Odd
