package compile.fail

import com.constraints.Constraint
import com.constraints.ConstraintException
import com.constraints.Validator
import com.constraints.checkConstraint

// A valid custom constraint (object, correct structure).
@Constraint(PositiveValidator::class)
@Target(AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Positive

object PositiveValidator : Validator<Int, Positive> {
    override fun validate(value: Int, annotation: Positive) {
        if (value <= 0) throw ConstraintException("must be positive")
    }
}

// A data-carrying constraint.
@Constraint(InverseRangeValidator::class)
@Target(AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class InverseRange(val min: Int, val max: Int)

object InverseRangeValidator : Validator<Int, InverseRange> {
    override fun validate(value: Int, annotation: InverseRange) {
        if (value in annotation.min..annotation.max)
            throw ConstraintException("must be outside ${annotation.min}..${annotation.max}")
    }
}

fun test() {
    // Plain literal -- no checkConstraint, validator is opaque.
    @Positive var a = 5                    // ERROR: Cannot prove this satisfies @Positive

    // Data-carrying constraint, no checkConstraint.
    @InverseRange(0, 10) var b = 20       // ERROR: Cannot prove this satisfies @InverseRange

    // Transfer between different @InverseRange arguments: not proven.
    @InverseRange(0, 10) val source = checkConstraint(20)
    @InverseRange(5, 20) val dest = source // ERROR: Cannot prove this satisfies @InverseRange
}

// A class (not an object) used as a validator: caught at the constraint definition site.
class BadValidator : Validator<Int, Positive> {
    override fun validate(value: Int, annotation: Positive) {}
}

@Constraint(BadValidator::class)           // ERROR: must be a Kotlin object
@Target(AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.SOURCE)
annotation class BadConstraint
