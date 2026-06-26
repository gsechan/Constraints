package com.constraints

/**
 * Alias for `@Size(1, Int.MAX_VALUE)` -- a non-empty sized value (CharSequence, Collection, Map, or
 * array). Replaces the former `@NonEmptyString`, `@NonEmptyCollection`, and `@NonEmptyArray`.
 */
@Size(1, Int.MAX_VALUE)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class NonEmpty
