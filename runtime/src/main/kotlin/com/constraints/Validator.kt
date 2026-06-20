package com.constraints

/**
 * Contract for `@ConstrainedBy` validators.
 *
 * Implement as a Kotlin `object` (singleton): the compiler plugin resolves the
 * class given to `@ConstrainedBy(...)` to its object instance and injects a call
 * to [validate] at every assignment to the annotated value.
 *
 * [validate] should return [value] unchanged when valid, or throw (e.g.
 * [ConstraintException]) when the constraint is broken.
 */
interface Validator {
    fun validate(value: Int): Int
}
