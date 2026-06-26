package com.constraints

/**
 * Runtime validator for [Size]; checks a value's size against the declared bounds.
 *
 * The sized types -- CharSequence, Collection, Map, and the array kinds -- share no common
 * size-bearing supertype, so the value is typed [Any] and dispatched explicitly. A value of none of
 * these types is a programming error (the plugin only targets sized values) and fails loudly.
 */
object SizeValidator : Validator<Any, Size> {
    override fun validate(value: Any, annotation: Size) {
        val size = when (value) {
            is CharSequence -> value.length
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
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
                "@Size can only be applied to a CharSequence, Collection, Map, or array, " +
                    "but got ${value::class.qualifiedName}"
            )
        }
        if (size < annotation.min || size > annotation.max) {
            throw ConstraintException(
                "@Size(${annotation.min}, ${annotation.max}) violated: " +
                    "size $size is not within ${annotation.min}..${annotation.max}"
            )
        }
    }
}
