package com.constraints.demo

import com.constraints.ConstrainedBy
import com.constraints.Validator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------------------------------------------------------------------------
// User-supplied validators -- Kotlin objects implementing Validator. The
// @ConstrainedBy annotation points at them by class; the plugin injects a call
// to validate() at each assignment to an annotated value.
// ---------------------------------------------------------------------------

object PositiveValidator : Validator {
    override fun validate(value: Int): Int {
        if (value <= 0) throw IllegalStateException("Must be positive, got $value")
        return value
    }
}

object EvenValidator : Validator {
    override fun validate(value: Int): Int {
        if (value % 2 != 0) throw IllegalStateException("Must be even, got $value")
        return value
    }
}

class ConstrainedByPluginTest {

    @Test
    fun `positive validator allows positive value`() {
        @ConstrainedBy(PositiveValidator::class) var x = 5
        assertEquals(5, x)
    }

    @Test
    fun `positive validator throws for zero`() {
        assertFailsWith<IllegalStateException> {
            @ConstrainedBy(PositiveValidator::class) var x = 0
            println(x)
        }
    }

    @Test
    fun `positive validator throws for negative`() {
        assertFailsWith<IllegalStateException> {
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
        assertFailsWith<IllegalStateException> {
            @ConstrainedBy(EvenValidator::class) var x = 3
            println(x)
        }
    }

    @Test
    fun `reassignment is also checked`() {
        @ConstrainedBy(PositiveValidator::class) var x = 1
        x = 10
        assertFailsWith<IllegalStateException> {
            x = -1
        }
    }
}
