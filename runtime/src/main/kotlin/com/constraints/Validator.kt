package com.constraints

/**
 * Contract for `@ConstrainedBy` validators.
 *
 * Implement as a Kotlin `object` (singleton). The constraint annotation that links to
 * this validator via `@ConstrainedBy(...)` (e.g. `@InverseRange(0, 10)`) is passed to
 * [validate] as [annotation], so the validator can read its parameters. [A] is that
 * annotation's type.
 *
 * The plugin injects the [validate] call inside the `checkConstraint(value)` escape
 * hatch. [validate] should return [value] unchanged when valid, or throw (e.g.
 * [ConstraintException]) when the constraint is broken.
 *
 * A validator MUST be a pure, stateless predicate on `(value, annotation)`: its result
 * must depend only on those arguments, with no mutable state. The plugin relies on this
 * to elide redundant checks -- it treats `(validator, annotation-and-its-arguments)` as
 * the identity of the constraint, so a value already known to satisfy an identical
 * constraint (e.g. read from another variable with the same annotation and arguments) is
 * assigned without re-checking. A stateful validator would make that unsound.
 */
interface Validator<T, A : Annotation> {
    fun validate(value: T, annotation: A): T
}
