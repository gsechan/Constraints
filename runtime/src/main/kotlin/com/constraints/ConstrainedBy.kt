package com.constraints

import kotlin.reflect.KClass

/**
 * Marks a value as constrained by a user-supplied [Validator] -- the
 * maximally-flexible escape hatch for constraints the built-ins can't express.
 *
 * [validator] is the class of a [Validator] implemented as a Kotlin `object`. Because
 * the validator is arbitrary runtime code, an assignment can never be proven safe at
 * compile time, so the plugin treats `@ConstrainedBy` like a non-null type that must be
 * explicitly checked: every assignment is a compile error unless the value is wrapped in
 * `checkConstraint(value)`. The plugin then injects the validator call into that escape
 * hatch; `validate` returns the value when valid or throws when the constraint is broken.
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
 *   var count: Int = checkConstraint(0)   // plain `= 0` would be a compile error
 *
 * It can also be used as a meta-annotation to define a named alias -- the plugin
 * follows `@ConstrainedBy` on an annotation class and injects the same validator
 * call for the alias:
 *
 *   @ConstrainedBy(Positive::class)
 *   @Target(AnnotationTarget.LOCAL_VARIABLE, ...)
 *   annotation class PositiveCount
 *
 *   @PositiveCount var count: Int = checkConstraint(0)   // injects Positive.validate(...)
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
