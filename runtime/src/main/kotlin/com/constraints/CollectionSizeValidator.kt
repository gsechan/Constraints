package com.constraints

/** Runtime validator for [CollectionSize]; checks [Collection.size] against the declared bounds. */
object CollectionSizeValidator : Validator<Collection<*>, CollectionSize> {
    override fun validate(value: Collection<*>, annotation: CollectionSize) {
        val size = value.size
        if (size < annotation.min || size > annotation.max) {
            throw ConstraintException(
                "@CollectionSize(${annotation.min}, ${annotation.max}) violated: " +
                    "size $size is not within ${annotation.min}..${annotation.max}"
            )
        }
    }
}
