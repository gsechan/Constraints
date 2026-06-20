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
 *   object Positive : Validator {
 *       override fun validate(value: Int): Int {
 *           if (value <= 0) throw ConstraintException("must be positive")
 *           return value
 *       }
 *   }
 *
 *   @ConstrainedBy(Positive::class)
 *   var count: Int = 0
 */
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class ConstrainedBy(val validator: KClass<out Validator>)
