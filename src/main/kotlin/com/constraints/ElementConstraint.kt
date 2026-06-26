package com.constraints

import kotlin.reflect.KClass

/**
 * Links a constraint annotation to an element-level [Validator] -- a validator that is applied to
 * every element of the annotated collection or array when `checkConstraint(value)` is called.
 * The target may be a [Collection] or any array (`Array<*>` or a primitive array like [IntArray]).
 *
 * `@ElementConstraint` is a meta-annotation: place it on an annotation class, never directly on a
 * value. The validator receives each element in turn and throws [ConstraintException] on the first
 * failure (throw-on-first). It is independent of `@Constraint` (the collection-level validator) --
 * a constraint annotation may carry both, either, or neither.
 *
 * Example:
 *
 *   @ElementConstraint(PositiveElementValidator::class)
 *   @Target(AnnotationTarget.LOCAL_VARIABLE, ...)
 *   annotation class AllPositive
 *
 *   object PositiveElementValidator : Validator<Int, AllPositive> {
 *       override fun validate(element: Int, annotation: AllPositive) {
 *           if (element <= 0) throw ConstraintException("All elements must be positive, got $element")
 *       }
 *   }
 *
 *   @AllPositive val scores: List<Int> = checkConstraint(someList)
 *
 * The transfer proof: if the RHS is a value already declared with the same `@ElementConstraint`
 * annotation (same annotation class and equal arguments), the constraint is proven statically
 * without injecting a runtime check.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ElementConstraint(val validator: KClass<out Validator<*, *>>)
