package com.constraints

/**
 * Thrown at runtime when a constrained value fails its constraint check.
 *
 * The [message] describes the specific failure -- which constraint was violated
 * and the offending value.
 */
class ConstraintException(message: String) : RuntimeException(message)
