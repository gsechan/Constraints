package com.constraints

/**
 * Shared range validator for floating point types, generic over the value type [Type] and its
 * annotation [AnnotationType]. Bounds are compared in [Double] -- Float→Double is lossless for
 * finite values, so [FloatRangeValidator] can use the same comparison logic.
 *
 * Unlike [RangeValidator] (which uses Long), this class does NOT use the existing Long-based
 * interval lattice; floating point ranges are validated by direct Double comparison at runtime.
 */
class FloatingPointRangeValidator<Type : Number, AnnotationType : Annotation>(
    private val min: (AnnotationType) -> Double,
    private val max: (AnnotationType) -> Double,
    private val name: String,
) : Validator<Type, AnnotationType> {
    override fun validate(value: Type, annotation: AnnotationType) {
        val lo = min(annotation)
        val hi = max(annotation)
        val v = value.toDouble()
        if (v < lo || v > hi) {
            throw ConstraintException("$name constraint violated: value $value is not within $lo..$hi")
        }
    }
}

object FloatRangeValidator : Validator<Float, FloatRange>
    by FloatingPointRangeValidator<Float, FloatRange>({ it.min.toDouble() }, { it.max.toDouble() }, "@FloatRange")

object DoubleRangeValidator : Validator<Double, DoubleRange>
    by FloatingPointRangeValidator<Double, DoubleRange>({ it.min }, { it.max }, "@DoubleRange")
