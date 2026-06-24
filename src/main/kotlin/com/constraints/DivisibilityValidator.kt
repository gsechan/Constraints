package com.constraints

/**
 * Shared divisibility validator, generic over the value type [Type] and its annotation
 * [AnnotationType]. Each `@DivisibleBy`-family constraint instantiates it with accessors for that
 * annotation's `divisor`/`remainder` and a display name. The math is done in [Long] with floored
 * modulo ([Long.mod]) -- lossless for Byte/Short/Int/Long, and matching the compiler plugin's
 * compile-time congruence check.
 *
 * The four constraints can't share a single `object` (the plugin needs one `@Constraint` validator
 * per annotation type), so each is a thin `object` that *delegates* its `validate` to a
 * `DivisibilityValidator` instance configured for its type.
 */
class DivisibilityValidator<Type : Number, AnnotationType : Annotation>(
    private val divisor: (AnnotationType) -> Long,
    private val remainder: (AnnotationType) -> Long,
    private val name: String,
) : Validator<Type, AnnotationType> {
    override fun validate(value: Type, annotation: AnnotationType) {
        val d = divisor(annotation)
        if (d == 0L) {
            throw ConstraintException("$name divisor must be non-zero")
        }
        val r = remainder(annotation)
        val actual = value.toLong().mod(d)
        val expected = r.mod(d)
        if (actual != expected) {
            throw ConstraintException(
                "$name($d, $r) violated: $value mod $d = $actual, expected $expected"
            )
        }
    }
}

object ByteDivisibleByValidator : Validator<Byte, ByteDivisibleBy>
    by DivisibilityValidator<Byte, ByteDivisibleBy>({ it.divisor.toLong() }, { it.remainder.toLong() }, "@ByteDivisibleBy")

object ShortDivisibleByValidator : Validator<Short, ShortDivisibleBy>
    by DivisibilityValidator<Short, ShortDivisibleBy>({ it.divisor.toLong() }, { it.remainder.toLong() }, "@ShortDivisibleBy")

object DivisibleByValidator : Validator<Int, DivisibleBy>
    by DivisibilityValidator<Int, DivisibleBy>({ it.divisor.toLong() }, { it.remainder.toLong() }, "@DivisibleBy")

object LongDivisibleByValidator : Validator<Long, LongDivisibleBy>
    by DivisibilityValidator<Long, LongDivisibleBy>({ it.divisor }, { it.remainder }, "@LongDivisibleBy")
