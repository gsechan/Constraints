package com.constraints

/**
 * Iterates over [target] and calls [validator].[Validator.validate] for each element, passing
 * [annotation] as the annotation instance. Throws [ConstraintException] on the first element that
 * fails (throw-on-first). Called by the compiler plugin inside the `checkConstraint(value)`
 * escape hatch when the target variable carries an `@ElementConstraint`.
 *
 * [target] may be a [Collection] or any array -- `Array<*>` or a primitive array (`IntArray`,
 * `DoubleArray`, …) -- which share no common element-iterating supertype, so each is dispatched
 * explicitly. The unchecked cast is safe: the plugin only generates a call here when the validator's
 * type parameter T matches the element type, as enforced at the annotation-definition site.
 */
@Suppress("UNCHECKED_CAST")
fun validateEachElement(target: Any, annotation: Annotation, validator: Validator<*, *>) {
    val v = validator as Validator<Any?, Annotation>
    when (target) {
        is Collection<*> -> target.forEach { v.validate(it, annotation) }
        is Array<*> -> target.forEach { v.validate(it, annotation) }
        is IntArray -> target.forEach { v.validate(it, annotation) }
        is LongArray -> target.forEach { v.validate(it, annotation) }
        is DoubleArray -> target.forEach { v.validate(it, annotation) }
        is FloatArray -> target.forEach { v.validate(it, annotation) }
        is ShortArray -> target.forEach { v.validate(it, annotation) }
        is ByteArray -> target.forEach { v.validate(it, annotation) }
        is CharArray -> target.forEach { v.validate(it, annotation) }
        is BooleanArray -> target.forEach { v.validate(it, annotation) }
        else -> throw ConstraintException(
            "@ElementConstraint can only be applied to a Collection or array, but got ${target::class.qualifiedName}"
        )
    }
}

/**
 * Applies [validator] to every value [depth] levels of nesting deep in [container], descending
 * through nested containers. `depth == 1` validates [container]'s direct elements; `depth == 2`
 * validates the elements of each (container) element, and so on. Used for an element-type constraint
 * on a nested generic, e.g. the `@IntRange` in `List<@Size(..) List<@IntRange(..) Int>>` -- or the
 * array equivalent `Array<@Size(..) Array<@IntRange(..) Int>>` -- is applied at depth 2.
 *
 * [container] may be a [Collection] or an `Array<*>` (object array); nesting may mix the two. Throws
 * [ConstraintException] on the first failure (throw-on-first).
 */
@Suppress("UNCHECKED_CAST")
fun validateEachAtDepth(container: Any, depth: Int, validator: Validator<*, *>, annotation: Annotation) {
    val elements: Iterable<Any?> = when (container) {
        is Collection<*> -> container
        is Array<*> -> container.asList()
        else -> return
    }
    if (depth <= 1) {
        val v = validator as Validator<Any?, Annotation>
        for (element in elements) v.validate(element, annotation)
    } else {
        for (element in elements) {
            if (element is Collection<*> || element is Array<*>) {
                validateEachAtDepth(element, depth - 1, validator, annotation)
            }
        }
    }
}
