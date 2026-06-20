package com.constraints

import kotlin.reflect.KClass

/**
 * Marks a value as constrained by a user-supplied [Validator] -- the
 * maximally-flexible escape hatch for constraints the built-ins can't express.
 *
 * [validator] is the class of a [Validator] implemented as a Kotlin `object`.
 * The compiler plugin injects a call to that validator's `validate` function at
 * every assignment to the annotated value; `validate` returns the value when
 * valid or throws when the constraint is broken.
 *
 * Example:
 *   object Positive : Validator<Int> {
 *       override fun validate(value: Int): Int {
 *           if (value <= 0) throw ConstraintException("must be positive")
 *           return value
 *       }
 *   }
 *
 *   @ConstrainedBy(Positive::class)
 *   var count: Int = 0
 *
 * It can also be used as a meta-annotation to define a named alias -- the plugin
 * follows `@ConstrainedBy` on an annotation class and injects the same validator
 * call for the alias:
 *
 *   @ConstrainedBy(Positive::class)
 *   @Target(AnnotationTarget.LOCAL_VARIABLE, ...)
 *   annotation class PositiveCount
 *
 *   @PositiveCount var count: Int = 0   // injects Positive.validate(...) just like the direct form
 */
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class ConstrainedBy(val validator: KClass<out Validator<*>>)
