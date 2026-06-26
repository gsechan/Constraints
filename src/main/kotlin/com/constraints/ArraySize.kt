package com.constraints

/**
 * Marks an array as having a size within [[min], [max]] inclusive: `value.size in min..max`.
 *
 * Works like [CollectionSize] but for arrays -- both `Array<T>` and the primitive array aliases
 * ([IntArray], [DoubleArray], [LongArray], [FloatArray], [ShortArray], [ByteArray], [CharArray],
 * [BooleanArray]).
 *
 * Note: arrays are fixed-size, so unlike a mutable collection the size can never change after
 * construction -- the bound, once proven, holds for the array's whole lifetime.
 *
 * Proven at compile time for:
 *  - Known-size factory calls: `arrayOf(a, b, c)` / `intArrayOf(1, 2, 3)` → size 3, `emptyArray()` → 0.
 *  - `arrayOfNulls(n)` with a literal `n` → size n.
 *  - `array + element` (single element): size = array.size + 1 (exact).
 *  - `array + otherArray` / `array + collection`: sum of sizes when both are known.
 *  - Transfer from a value declared with the same or a narrower `@ArraySize`.
 *
 * Anything else (dynamic sizes, unknown arrays) requires `checkConstraint(value)`.
 *
 * It may also annotate a return type -- `fun ids(): @ArraySize(1, 10) IntArray`.
 */
@Constraint(ArraySizeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class ArraySize(val min: Int, val max: Int)
