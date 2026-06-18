package com.constraints

/**
 * Marks a value as constrained by a user-supplied validator function -- the
 * maximally-flexible escape hatch for constraints the built-ins can't express
 * (arbitrary business logic).
 *
 * [validator] is the fully-qualified name of a top-level function with the
 * signature `(Int) -> Int`. The compiler plugin resolves that name to a symbol
 * and injects a call to it at every assignment site; the function should return
 * the value unchanged when valid, or throw (e.g. [IllegalStateException]) when
 * the constraint is broken.
 *
 * Example:
 *   @ConstrainedBy("com.myapp.validatePositive")
 *   var count: Int = 0
 */
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class ConstrainedBy(val validator: String)
