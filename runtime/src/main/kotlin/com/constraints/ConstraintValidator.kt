package com.constraints

/**
 * Runtime check for a single constraint annotation of type [A].
 *
 * Implement as a Kotlin `object`. The constraint annotation it validates is linked
 * to it via [CompileTimeConstraint]; the compiler plugin reads that link and, for a
 * `checkConstraint(value)` call, invokes [validate] with the value and the actual
 * annotation instance (so the validator can read its parameters, e.g. an
 * `@IntRange`'s `min`/`max`).
 *
 * [validate] returns nothing: it should return normally when the value is valid, or throw
 * [ConstraintException] when the constraint is broken. It cannot transform the value -- a
 * constraint validates, it does not coerce.
 */
interface ConstraintValidator<A : Annotation> {
    fun validate(value: Int, annotation: A)
}
