package com.constraints

import kotlin.reflect.KClass

/**
 * Links a *constraint annotation* to the [Validator] that enforces it -- the
 * maximally-flexible way to define constraints the built-ins can't express.
 *
 * `@ConstrainedBy` is a meta-annotation: place it on an annotation class, never directly
 * on a value (its only target is `ANNOTATION_CLASS`). Annotating a value with the
 * resulting constraint then runs [validator]. Because the validator is arbitrary runtime
 * code, an assignment can never be proven safe at compile time, so the plugin treats the
 * constraint like a non-null type that must be explicitly checked: every assignment is a
 * compile error unless the value is wrapped in `checkConstraint(value)`. The plugin injects
 * the validator call into that escape hatch; `validate` returns the value when valid or
 * throws when the constraint is broken.
 *
 * A no-data constraint:
 *
 *   @ConstrainedBy(PositiveValidator::class)
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
 * A data-carrying constraint -- the plugin passes the annotation instance to `validate`, so
 * the validator can read its parameters:
 *
 *   @ConstrainedBy(InverseRangeValidator::class)
 *   @Target(AnnotationTarget.LOCAL_VARIABLE, ...)
 *   annotation class InverseRange(val min: Int, val max: Int)
 *
 *   object InverseRangeValidator : Validator<Int, InverseRange> {
 *       override fun validate(value: Int, annotation: InverseRange) {
 *           if (value in annotation.min..annotation.max)
 *               throw ConstraintException("must be outside ${annotation.min}..${annotation.max}")
 *       }
 *   }
 *
 *   @InverseRange(0, 10) var x: Int = checkConstraint(20)
 *
 * Two values share a constraint (and so transfer between them needs no runtime check)
 * only when their annotations are the same class with all arguments equal -- e.g.
 * `@InverseRange(0, 10)` matches `@InverseRange(0, 10)` but not `@InverseRange(5, 20)`.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ConstrainedBy(val validator: KClass<out Validator<*, *>>)
