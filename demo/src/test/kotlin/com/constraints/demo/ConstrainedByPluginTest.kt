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
// @ConstrainedBy annotation points at them by class; the plugin injects a call
// to validate() at each assignment to an annotated value.
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
        @ConstrainedBy(PositiveValidator::class) var x = 5
        assertEquals(5, x)
    }

    @Test
    fun `positive validator throws for zero`() {
        assertFailsWith<ConstraintException> {
            @ConstrainedBy(PositiveValidator::class) var x = 0
            println(x)
        }
    }

    @Test
    fun `positive validator throws for negative`() {
        assertFailsWith<ConstraintException> {
            @ConstrainedBy(PositiveValidator::class) var x = -3
            println(x)
        }
    }

    @Test
    fun `even validator allows even value`() {
        @ConstrainedBy(EvenValidator::class) var x = 4
        assertEquals(4, x)
    }

    @Test
    fun `even validator throws for odd value`() {
        assertFailsWith<ConstraintException> {
            @ConstrainedBy(EvenValidator::class) var x = 3
            println(x)
        }
    }

    @Test
    fun `reassignment is also checked`() {
        @ConstrainedBy(PositiveValidator::class) var x = 1
        x = 10
        assertFailsWith<ConstraintException> {
            x = -1
        }
    }

    @Test
    fun `constrainedby alias allows valid value`() {
        @Even var x = 4
        assertEquals(4, x)
    }

    @Test
    fun `constrainedby alias throws for invalid value`() {
        assertFailsWith<ConstraintException> {
            @Even var x = 3
            println(x)
        }
    }

    @Test
    fun `constrainedby alias checks reassignment`() {
        @Even var x = 2
        x = 8
        assertFailsWith<ConstraintException> {
            x = 5
        }
    }

    @Test
    fun `test multiple constraints all check`() {
        var n =12
        @ConstrainedBy(EvenValidator::class)
        @IntRange(0,10) var x = checkConstraint(2)
        //Test changing 2 to 1 and 12
    }
}
