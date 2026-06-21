package com.constraints

import kotlin.reflect.KClass

/**
 * Meta-annotation that marks an annotation as a **compile-time constraint** and
 * names the [Validator] that checks it at runtime.
 *
 * Put it on a constraint annotation (e.g. `@IntRange`). `checkConstraint(value)`
 * then walks every annotation on the value, and for each one carrying this
 * meta-annotation, chains a call to its [validator]'s `validate(value, annotation)`.
 * Adding a new constraint needs no plugin changes -- just annotate it with this and
 * supply a validator.
 *
 * Retained as [AnnotationRetention.BINARY] so the plugin can read it off the
 * constraint annotation across module boundaries.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class CompileTimeConstraint(val validator: KClass<out Validator<*, *>>)
