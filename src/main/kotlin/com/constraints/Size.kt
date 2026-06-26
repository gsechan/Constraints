package com.constraints

/**
 * Marks a sized value as having a size within [[min], [max]] inclusive.
 *
 * Works across every sized type the library understands -- the meaning of "size" is the natural one
 * for each:
 *  - [CharSequence] (String, StringBuilder, …): `value.length`.
 *  - [Collection] / [Map]: `value.size`.
 *  - Arrays -- `Array<T>` and the primitive aliases ([IntArray], [DoubleArray], …): `value.size`.
 *
 * (Replaces the former `@StringLength`, `@CollectionSize`, and `@ArraySize` annotations.)
 *
 * Proven at compile time for:
 *  - String literals (exact length) and concatenation (`+`) of known-length strings.
 *  - Known-size factory calls: `listOf(a, b, c)` / `arrayOf(a, b, c)` / `intArrayOf(1, 2, 3)` → 3,
 *    `emptyList()` / `emptyArray()` → 0, `mapOf(...)` → entry count.
 *  - Array constructors with a known size: `IntArray(n)` / `Array(n) { … }` → n; `arrayOfNulls(n)`.
 *  - `container + element` (size + 1) and `container + otherContainer` (sum) for collections/arrays;
 *    `collection - element` / `collection - otherCollection` for collections.
 *  - Transfer from a value declared with the same or a narrower `@Size`.
 *
 * Anything else (dynamic input, string interpolation, unknown sizes) requires `checkConstraint(value)`.
 *
 * It may also annotate a return type -- `fun label(): @Size(1, 20) String`.
 */
@Constraint(SizeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class Size(val min: Int, val max: Int)
