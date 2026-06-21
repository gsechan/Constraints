package com.constraints

/**
 * Marks an Int value as constrained to the inclusive range [[min], [max]].
 *
 * The compiler plugin checks this at COMPILE TIME: an assignment is allowed only
 * if the value is provably within `[min, max]` (an in-range literal, a narrower
 * @IntRange value, interval-safe arithmetic, or an explicit [checkConstraint]
 * call). Otherwise it is a compile error.
 *
 * It may also annotate a return type -- `fun f(): @IntRange(0, 5) Int` -- in
 * which case callers may trust the result without a runtime check, and the
 * function's `return`s are themselves checked against the range.
 */
@CompileTimeConstraint(IntRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY) // BINARY so the plugin can read it off an alias class cross-module
annotation class IntRange(val min: Int = Int.MIN_VALUE, val max: Int = Int.MAX_VALUE)

