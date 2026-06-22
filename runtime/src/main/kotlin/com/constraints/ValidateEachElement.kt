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
