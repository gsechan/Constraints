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
// User-supplied validators -- Kotlin objects implementing Validator. The
// @ConstrainedBy annotation points at them by class. Because the validator runs
// at runtime and can't be proven statically, every assignment to an annotated
// value is a compile error unless wrapped in checkConstraint(value); the plugin
// injects the validate() call into that escape hatch.
// ---------------------------------------------------------------------------

object PositiveValidator : Validator<Int> {
    override fun validate(value: Int): Int {
        if (value <= 0) throw ConstraintException("Must be positive, got $value")
        return value
    }
}

object EvenValidator : Validator<Int> {
    override fun validate(value: Int): Int {
        if (value % 2 != 0) throw ConstraintException("Must be even, got $value")
        return value
    }
}

// A named alias defined by meta-annotating with @ConstrainedBy: using @Even is
// equivalent to @ConstrainedBy(EvenValidator::class) -- the plugin follows the
// meta-annotation and injects the same EvenValidator.validate(...) call.
@ConstrainedBy(EvenValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Even

class ConstrainedByPluginTest {

    @Test
    fun `positive validator allows positive value`() {
        @ConstrainedBy(PositiveValidator::class) var x = checkConstraint(5)
        assertEquals(5, x)
    }

    @Test
    fun `positive validator throws for zero`() {
        assertFailsWith<ConstraintException> {
            @ConstrainedBy(PositiveValidator::class) var x = checkConstraint(0)
            println(x)
        }
    }

    @Test
    fun `positive validator throws for negative`() {
        assertFailsWith<ConstraintException> {
            @ConstrainedBy(PositiveValidator::class) var x = checkConstraint(-3)
            println(x)
        }
    }

    @Test
    fun `even validator allows even value`() {
        @ConstrainedBy(EvenValidator::class) var x = checkConstraint(4)
        assertEquals(4, x)
    }

    @Test
    fun `even validator throws for odd value`() {
        assertFailsWith<ConstraintException> {
            @ConstrainedBy(EvenValidator::class) var x = checkConstraint(3)
            println(x)
        }
    }

    @Test
    fun `reassignment is also checked`() {
        @ConstrainedBy(PositiveValidator::class) var x = checkConstraint(1)
        x = checkConstraint(10)
        assertFailsWith<ConstraintException> {
            x = checkConstraint(-1)
        }
    }

    @Test
    fun `constrainedby alias allows valid value`() {
        @Even var x = checkConstraint(4)
        assertEquals(4, x)
    }

    @Test
    fun `constrainedby alias throws for invalid value`() {
        assertFailsWith<ConstraintException> {
            @Even var x = checkConstraint(3)
            println(x)
        }
    }

    @Test
    fun `constrainedby alias checks reassignment`() {
        @Even var x = checkConstraint(2)
        x = checkConstraint(8)
        assertFailsWith<ConstraintException> {
            x = checkConstraint(5)
        }
    }

    @Test
    fun `test multiple constraints all check`() {
        @ConstrainedBy(EvenValidator::class)
        @IntRange(0,10) var x = checkConstraint(2)
        assertEquals(2, x)
    }

    @Test
    fun `transfer between same-validator values needs no runtime check`() {
        // `a` is validated once at runtime; `b = a` is *proven* at compile time because
        // `a` is already known to satisfy PositiveValidator -- no checkConstraint, and the
        // plugin injects no validate() call for the second assignment.
        @ConstrainedBy(PositiveValidator::class) val a = checkConstraint(5)
        @ConstrainedBy(PositiveValidator::class) val b = a
        assertEquals(5, b)
    }

    @Test
    fun `transfer works through an alias to the same validator`() {
        // @Even and @ConstrainedBy(EvenValidator::class) share the EvenValidator class, so
        // the constraint identity matches and the transfer is proven.
        @Even val a = checkConstraint(4)
        @ConstrainedBy(EvenValidator::class) val b = a
        assertEquals(4, b)
    }


    // -----------------------------------------------------------------------
    // These do NOT compile -- a @ConstrainedBy value can only be assigned from a
    // checkConstraint(value) call or from another value already known to satisfy the
    // SAME validator. A literal, arithmetic, or differently-validated source is rejected:
    //   @ConstrainedBy(PositiveValidator::class) var x = 5            // error: opaque literal
    //   @Even var y = 4                                              // error: opaque literal
    //   @ConstrainedBy(EvenValidator::class) val z = positiveValue   // error: wrong validator
    //   x = x + 1                                                    // error: arithmetic isn't tracked
    // -----------------------------------------------------------------------
}
