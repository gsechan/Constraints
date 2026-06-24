package com.constraints

import kotlin.reflect.KClass

/**
 * Marks an annotation as a *constraint* and names the [Validator] that enforces it.
 *
 * Place it on an annotation class (its only target is `ANNOTATION_CLASS`); annotating a value
 * with the resulting constraint then runs [validator]. When the value is assigned through the
 * `checkConstraint(value)` escape hatch, the plugin injects a call to
 * `validator.validate(value, annotation)` -- passing the annotation instance, so a data-carrying
 * constraint can read its parameters.
 *
 * The plugin proves a few built-in constraints (`@IntRange`, `@DivisibleBy`, and their `Long`
 * twins) at compile time and elides the runtime check when it can. Every other constraint is
 * runtime-only: because its validator is arbitrary code, an assignment can't be proven safe at
 * compile time, so the plugin requires either `checkConstraint(value)` or a value already known
 * to satisfy the identical constraint. (Two values share a constraint only when their annotations
 * are the same class with all arguments equal -- `@InverseRange(0, 10)` matches `@InverseRange(0,
 * 10)` but not `@InverseRange(5, 20)`.)
 *
 * A no-data custom constraint:
 *
 *   @Constraint(PositiveValidator::class)
 *   @Target(AnnotationTarget.LOCAL_VARIABLE, ...)
 *   annotation class Positive
 *
 *   object PositiveValidator : Validator<Int, Positive> {
 *       override fun validate(value: Int, annotation: Positive) {
 *           if (value <= 0) throw ConstraintException("must be positive")
 *       }
 *   }
 *
 *   @Positive var count: Int = checkConstraint(0)   // plain `= 0` would be a compile error
 *
 * A data-carrying custom constraint -- the validator reads its parameters off the annotation:
 *
 *   @Constraint(InverseRangeValidator::class)
 *   @Target(AnnotationTarget.LOCAL_VARIABLE, ...)
 *   annotation class InverseRange(val min: Int, val max: Int)
 *
 *   @InverseRange(0, 10) var x: Int = checkConstraint(20)
 *
 * Retained as [AnnotationRetention.BINARY] so the plugin can read it off a constraint annotation
 * across module boundaries.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Constraint(val validator: KClass<out Validator<*, *>>)
