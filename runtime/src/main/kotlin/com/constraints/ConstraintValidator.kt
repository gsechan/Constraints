package com.constraints

/**
 * Runtime check for a single constraint annotation of type [A].
 *
 * When a constraint needs to be checked at runtime, the validate function is called
 * and passed in the value to validate and the annotation that is being validated.
 * The annotation is passed so you can place parameters on a specific instance and use
 * them in validation, such as integer ranges.  The validate method must throw a ConstrantException
 * when the validation fails
 *
 * IT IS VERY IMPORTANT THAT A VALIDATOR IS A CLOSED SYSTEM.  It should use no
 * state or external data outside of the value itself and the annotation to evaluate
 * the validity of the value.  Using any outside data can break compile time analysis,
 * which assumes that the same validatior and same values on the annotation is the same
 * constraint at all times.
 *
 */
interface ConstraintValidator<T, A : Annotation> {
    fun validate(value: T, annotation: A)
}
