package com.constraints

/**
 * Universal constraint escape hatch. The compiler plugin rewrites this call to
 * apply every constraint declared on the value being assigned to -- its
 * `@IntRange` bounds, and (via the `@ConstrainedBy` injection that wraps the
 * assignment) its validators -- in a single call.
 *
 * Only valid as the direct initializer/assignment of a constrained value; anywhere
 * else the plugin can't supply the constraints and it throws.
 */
@Suppress("UNUSED_PARAMETER")
fun checkConstraint(value: Int): Int =
    throw IllegalStateException(
        "checkConstraint(value) must initialise a constrained value so the compiler can supply its constraints"
    )
