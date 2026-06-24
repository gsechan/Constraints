package com.constraints

/**
 * Universal constraint escape hatch. The compiler plugin rewrites this call to run the
 * [Validator] of every `@Constraint` declared on the value being assigned to -- evaluating
 * the value once and checking each constraint against it.
 *
 * Only valid as the direct initializer/assignment of a constrained value; anywhere
 * else the plugin can't supply the constraints and it throws.
 *
 * Generic in the value type [T], so it works for any constrained type (`Int` for `@IntRange`,
 * `Long` for `@LongRange`, ...) -- the plugin reads the actual type from the call site.
 */
@Suppress("UNUSED_PARAMETER")
fun <T> checkConstraint(value: T): T =
    throw IllegalStateException(
        "checkConstraint(value) must initialise a constrained value so the compiler can supply its constraints"
    )

/**
 * Constraint escape hatch with a fallback -- the runtime analog of the elvis operator. Like
 * [checkConstraint], the plugin rewrites this call to run the [Validator] of every `@Constraint`
 * declared on the value being assigned to; but if any validator throws [ConstraintException], the
 * exception is caught and [default] is returned instead.
 *
 * [default] is returned as-is and is **not** itself validated, so supply a value you know satisfies
 * the constraint (typically a constant). [default] is only evaluated when a validator fails.
 *
 * Only valid as the direct initializer/assignment of a constrained value; anywhere else the plugin
 * can't supply the constraints and it throws.
 */
@Suppress("UNUSED_PARAMETER")
fun <T> checkConstraintOrDefault(value: T, default: T): T =
    throw IllegalStateException(
        "checkConstraintOrDefault(value, default) must initialise a constrained value so the compiler can supply its constraints"
    )
