package com.constraints

/**
 * Contract for `@ConstrainedBy` validators.
 *
 * Implement as a Kotlin `object` (singleton): the compiler plugin resolves the
 * class given to `@ConstrainedBy(...)` to its object instance and injects a call
 * to [validate] inside the `checkConstraint(value)` escape hatch.
 *
 * [validate] should return [value] unchanged when valid, or throw (e.g.
 * [ConstraintException]) when the constraint is broken.
 *
 * A validator MUST be a pure, stateless predicate on its argument: `validate(x)`
 * must succeed-or-fail based only on `x`, with no mutable state and no dependence on
 * anything else. The plugin relies on this to elide redundant checks -- it treats the
 * validator class as the identity of the constraint, so a value already known to
 * satisfy a validator (e.g. read from another variable with the same `@ConstrainedBy`)
 * is assigned without re-checking. A stateful validator would make that unsound.
 */
interface Validator<T> {
    fun validate(value: T): T
}
