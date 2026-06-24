package compile.fail

import com.constraints.ConstraintException
import com.constraints.ElementConstraint
import com.constraints.Validator
import com.constraints.checkConstraint

@ElementConstraint(PositiveElemValidator::class)
@Target(AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class AllPositive

object PositiveElemValidator : Validator<Int, AllPositive> {
    override fun validate(value: Int, annotation: AllPositive) {
        if (value <= 0) throw ConstraintException("element must be positive")
    }
}

@ElementConstraint(EvenElemValidator::class)
@Target(AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class AllEven

object EvenElemValidator : Validator<Int, AllEven> {
    override fun validate(value: Int, annotation: AllEven) {
        if (value % 2 != 0) throw ConstraintException("element must be even")
    }
}

fun test() {
    // No checkConstraint: elements can't be proven statically.
    @AllPositive val a = listOf(1, 2, 3)  // ERROR: Cannot prove all elements

    // Dynamic source: elements can't be proven.
    val items = listOf(1, 2, 3)
    @AllPositive val b = items             // ERROR: Cannot prove all elements

    // Different element constraint: @AllEven is not @AllPositive.
    @AllEven val source = checkConstraint(listOf(2, 4))
    @AllPositive val c = source            // ERROR: Cannot prove all elements
}
