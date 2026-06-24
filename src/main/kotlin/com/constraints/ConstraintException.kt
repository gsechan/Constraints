package com.constraints

/**
 * Thrown at runtime when a constrained value fails its constraint check.
 */
class ConstraintException(message: String) : RuntimeException(message)
