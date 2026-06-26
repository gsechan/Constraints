package com.constraints

/**
 * Iterates over [collection] and calls [validator].[Validator.validate] for each element, passing
 * [annotation] as the annotation instance. Throws [ConstraintException] on the first element that
 * fails (throw-on-first). Called by the compiler plugin inside the `checkConstraint(collection)`
 * escape hatch when the target variable carries an `@ElementConstraint`.
 *
 * The unchecked cast is safe: the plugin only generates a call here when the validator's type
 * parameter T matches the collection's element type, as enforced at the annotation-definition site.
 */
@Suppress("UNCHECKED_CAST")
fun validateEachElement(collection: Collection<*>, annotation: Annotation, validator: Validator<*, *>) {
    val v = validator as Validator<Any?, Annotation>
    for (element in collection) {
        v.validate(element, annotation)
    }
}

/**
 * Applies [validator] to every value [depth] levels of nesting deep in [collection], descending
 * through nested collections. `depth == 1` validates [collection]'s direct elements; `depth == 2`
 * validates the elements of each (collection) element, and so on. Used for an element-type
 * constraint on a nested generic, e.g. the `@IntRange` in `List<@CollectionSize(..) List<@IntRange(..) Int>>`
 * is applied at depth 2. Throws [ConstraintException] on the first failure (throw-on-first).
 */
@Suppress("UNCHECKED_CAST")
fun validateEachAtDepth(collection: Collection<*>, depth: Int, validator: Validator<*, *>, annotation: Annotation) {
    if (depth <= 1) {
        val v = validator as Validator<Any?, Annotation>
        for (element in collection) v.validate(element, annotation)
    } else {
        for (element in collection) {
            if (element is Collection<*>) validateEachAtDepth(element, depth - 1, validator, annotation)
        }
    }
}
