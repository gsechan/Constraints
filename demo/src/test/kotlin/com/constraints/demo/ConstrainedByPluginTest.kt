package com.constraints.demo

import com.constraints.ConstrainedBy
import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.Validator
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------------------------------------------------------------------------
// @ConstrainedBy is a meta-annotation: it links a constraint annotation to its
// Validator<T, A>. You annotate a value with the constraint (e.g. @Positive,
// @InverseRange(0, 10)), never with @ConstrainedBy directly. The plugin passes the
// constraint annotation instance to validate(), so a data-carrying constraint can read
// its parameters.
//
// Every assignment to a constrained value is a compile error unless wrapped in
// checkConstraint(value); the plugin injects the validate() call into that escape hatch.
// ---------------------------------------------------------------------------

@ConstrainedBy(PositiveValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class Positive

object PositiveValidator : Validator<Int, Positive> {
    override fun validate(value: Int, annotation: Positive) {
        if (value <= 0) throw ConstraintException("Must be positive, got $value")
    }
}

@ConstrainedBy(EvenValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class Even

object EvenValidator : Validator<Int, Even> {
    override fun validate(value: Int, annotation: Even) {
        if (value % 2 != 0) throw ConstraintException("Must be even, got $value")
    }
}

// A data-carrying constraint: the inverse of @IntRange -- the value must be OUTSIDE [min, max].
// The validator reads min/max off the annotation instance it is handed.
@ConstrainedBy(InverseRangeValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class InverseRange(val min: Int, val max: Int)

object InverseRangeValidator : Validator<Int, InverseRange> {
    override fun validate(value: Int, annotation: InverseRange) {
        if (value in annotation.min..annotation.max)
            throw ConstraintException("Must be outside ${annotation.min}..${annotation.max}, got $value")
    }
}

class ConstrainedByPluginTest {

    @Test
    fun `positive allows positive value`() {
        @Positive var x = checkConstraint(5)
        assertEquals(5, x)
    }

    @Test
    fun `positive throws for zero`() {
        assertFailsWith<ConstraintException> {
            @Positive var x = checkConstraint(0)
            println(x)
        }
    }

    @Test
    fun `positive throws for negative`() {
        assertFailsWith<ConstraintException> {
            @Positive var x = checkConstraint(-3)
            println(x)
        }
    }

    @Test
    fun `reassignment is also checked`() {
        @Positive var x = checkConstraint(1)
        x = checkConstraint(10)
        assertFailsWith<ConstraintException> {
            x = checkConstraint(-1)
        }
    }

    @Test
    fun `even allows even value`() {
        @Even var x = checkConstraint(4)
        assertEquals(4, x)
    }

    @Test
    fun `even throws for odd value`() {
        assertFailsWith<ConstraintException> {
            @Even var x = checkConstraint(3)
            println(x)
        }
    }

    @Test
    fun `even checks reassignment`() {
        @Even var x = checkConstraint(2)
        x = checkConstraint(8)
        assertFailsWith<ConstraintException> {
            x = checkConstraint(5)
        }
    }

    @Test
    fun `inverse range allows value outside the range`() {
        // The validator reads min=0, max=10 off the annotation; 20 is outside -> valid.
        @InverseRange(0, 10) val x = checkConstraint(20)
        assertEquals(20, x)
    }

    @Test
    fun `inverse range throws for value inside the range`() {
        assertFailsWith<ConstraintException> {
            @InverseRange(0, 10) val x = checkConstraint(5)
            println(x)
        }
    }

    @Test
    fun `test multiple constraints all check`() {
        @Positive
        @IntRange(0, 10) var x = checkConstraint(2)
        assertEquals(2, x)
    }

    @Test
    fun `transfer between same-constraint values needs no runtime check`() {
        // `a` is validated once at runtime; `b = a` is *proven* at compile time because
        // `a` is already known to satisfy the identical constraint -- no checkConstraint,
        // and the plugin injects no validate() call for the second assignment.
        @Positive val a = checkConstraint(5)
        @Positive val b = a
        assertEquals(5, b)
    }

    @Test
    fun `transfer between identical data annotations is proven`() {
        // Same annotation class AND equal arguments (0, 10) -> the constraints match, so the
        // transfer is proven with no runtime check.
        @InverseRange(0, 10) val a = checkConstraint(20)
        @InverseRange(0, 10) val b = a
        assertEquals(20, b)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @ConstrainedBy(PositiveValidator::class) val w = 5     // error: @ConstrainedBy targets
    //                                                          //        ANNOTATION_CLASS, not values
    //   @Positive var x = 5                                    // error: opaque literal, not validated
    //   @InverseRange(0, 10) var y = 20                        // error: opaque literal, not validated
    //   @InverseRange(5, 20) val z = inverseRange0to10Value    // error: arguments differ (5,20) vs (0,10)
    //   x = x + 1                                              // error: arithmetic isn't tracked
    // -----------------------------------------------------------------------
}
