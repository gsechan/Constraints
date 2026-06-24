package com.constraints

/**
 * Marks an immutable [Collection] as having a size within [[min], [max]] inclusive:
 * `value.size in min..max`.
 *
 * Note: do not use on [MutableCollection] -- mutations bypass the validator.
 *
 * Proven at compile time for:
 *  - Known-size factory calls: `listOf(a, b, c)` → size 3, `emptyList()` → size 0.
 *  - `collection + element` (single element): size = collection.size + 1 (exact).
 *  - `collection + otherCollection`: sum of sizes when both have known `@CollectionSize`.
 *  - `collection - element` (single element): removes at most one → [max(0, min-1), max].
 *  - `collection - otherCollection`: removes at most the other's size → [max(0, min-other.max), max].
 *  - Transfer from a value declared with the same or a narrower `@CollectionSize`.
 *
 * Anything else (dynamic queries, filtered views, unknown collections) requires
 * `checkConstraint(value)`.
 *
 * It may also annotate a return type -- `fun tags(): @CollectionSize(1, 10) List<String>`.
 */
@Constraint(CollectionSizeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.BINARY)
annotation class CollectionSize(val min: Int, val max: Int)
