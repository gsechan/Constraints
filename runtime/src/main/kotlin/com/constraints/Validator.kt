package com.constraints

/**
 * Contract for constraint validators (the validator named by a `@Constraint` annotation).
 *
 * Validators confirm whether or not a value is allowed for an instance of a constraint.
 * Validators must be stateless and not use outside values to validate-  using state or
 * other values may break static compile time analysis, which assumes that two variables
 * with equal annotations and the same validator are the same constraint.
 *
 * validate must throw a ConstraintException if the value passed in is not valid for the
 * constraint.  validate will be called whenever checkConstraint is called for an annotation
 * which specifies this as the validator.
 */
interface Validator<T, A : Annotation> {
    fun validate(value: T, annotation: A)
}
