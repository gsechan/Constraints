package com.constraints

/**
 * Runtime validator for [ArraySize]; checks an array's `size` against the declared bounds.
 *
 * Arrays have no common supertype exposing `size`, so the value is typed [Any] and dispatched over
 * `Array<*>` plus the eight primitive array types. A non-array value is a programming error (the
 * plugin only targets arrays) and fails loudly.
 */
object ArraySizeValidator : Validator<Any, ArraySize> {
    override fun validate(value: Any, annotation: ArraySize) {
        val size = when (value) {
            is Array<*> -> value.size
            is IntArray -> value.size
            is DoubleArray -> value.size
            is LongArray -> value.size
            is FloatArray -> value.size
            is ShortArray -> value.size
            is ByteArray -> value.size
            is CharArray -> value.size
            is BooleanArray -> value.size
            else -> throw ConstraintException(
                "@ArraySize can only be applied to arrays, but got ${value::class.qualifiedName}"
            )
        }
        if (size < annotation.min || size > annotation.max) {
            throw ConstraintException(
                "@ArraySize(${annotation.min}, ${annotation.max}) violated: " +
                    "size $size is not within ${annotation.min}..${annotation.max}"
            )
        }
    }
}
