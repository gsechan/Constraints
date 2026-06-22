package com.constraints

/**
 * Shared range validator, generic over the value type [Type] and its annotation [AnnotationType].
 * Each numeric range constraint instantiates it with accessors for that annotation's `min`/`max`
 * and a display name; bounds are compared in [Long], which is lossless for Byte/Short/Int/Long.
 *
 * The four range constraints can't share a single `object` (the plugin needs one `@Constraint`
 * validator per annotation type), so each is a thin `object` that *delegates* its `validate` to a
 * `RangeValidator` instance configured for its type.
 */
class RangeValidator<Type : Number, AnnotationType : Annotation>(
    private val min: (AnnotationType) -> Long,
    private val max: (AnnotationType) -> Long,
    private val name: String,
) : Validator<Type, AnnotationType> {
    override fun validate(value: Type, annotation: AnnotationType) {
        val lo = min(annotation)
        val hi = max(annotation)
        val v = value.toLong()
        if (v < lo || v > hi) {
            throw ConstraintException("$name constraint violated: value $value is not within $lo..$hi")
        }
    }
}

object ByteRangeValidator : Validator<Byte, ByteRange>
    by RangeValidator<Byte, ByteRange>({ it.min.toLong() }, { it.max.toLong() }, "@ByteRange")

object ShortRangeValidator : Validator<Short, ShortRange>
    by RangeValidator<Short, ShortRange>({ it.min.toLong() }, { it.max.toLong() }, "@ShortRange")

object IntRangeValidator : Validator<Int, IntRange>
    by RangeValidator<Int, IntRange>({ it.min.toLong() }, { it.max.toLong() }, "@IntRange")

object LongRangeValidator : Validator<Long, LongRange>
    by RangeValidator<Long, LongRange>({ it.min }, { it.max }, "@LongRange")
