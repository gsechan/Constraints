package com.constraints

/**
 * Marks an Int value as being divisible by the divisor with exactly the requested remainder.
 *
 * This is the same as % for numbers >=0.  For negative numbers, the remainer is the distance
 * to the next most negative multiple of divisor.  So -1 with a divisor of 3 would be a remainder of 2
 * This was chosen to allow greater compile time analysis, as the expected use of this is on positive numbers
 *
 */
@Constraint(DivisibleByValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class DivisibleBy(val divisor: Int, val remainder: Int = 0)
