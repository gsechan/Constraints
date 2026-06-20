package com.constraints

/** Alias for `@DivisibleBy(2, 0)` -- an even Int. */
@DivisibleBy(2, 0)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Even
