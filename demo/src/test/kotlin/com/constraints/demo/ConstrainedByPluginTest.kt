package com.constraints.demo

import com.constraints.ConstrainedBy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------------------------------------------------------------------------
// User-supplied validators -- ordinary functions; the @ConstrainedBy annotation
// just points at them by fully-qualified name. Signature must be (Int) -> Int.
// ---------------------------------------------------------------------------

fun validatePositive(value: Int): Int {
    if (value <= 0) throw IllegalStateException("Must be positive, got $value")
    return value
}

fun validateEven(value: Int): Int {
    if (value % 2 != 0) throw IllegalStateException("Must be even, got $value")
    return value
}

class ConstrainedByPluginTest {

    @Test
    fun `positive validator allows positive value`() {
        @ConstrainedBy("com.constraints.demo.validatePositive") var x = 5
        assertEquals(5, x)
    }

    @Test
    fun `positive validator throws for zero`() {
        assertFailsWith<IllegalStateException> {
            @ConstrainedBy("com.constraints.demo.validatePositive") var x = 0
            println(x)
        }
    }

    @Test
    fun `positive validator throws for negative`() {
        assertFailsWith<IllegalStateException> {
            @ConstrainedBy("com.constraints.demo.validatePositive") var x = -3
            println(x)
        }
    }

    @Test
    fun `even validator allows even value`() {
        @ConstrainedBy("com.constraints.demo.validateEven") var x = 4
        assertEquals(4, x)
    }

    @Test
    fun `even validator throws for odd value`() {
        assertFailsWith<IllegalStateException> {
            @ConstrainedBy("com.constraints.demo.validateEven") var x = 3
            println(x)
        }
    }

    @Test
    fun `reassignment is also checked`() {
        @ConstrainedBy("com.constraints.demo.validatePositive") var x = 1
        x = 10
        assertFailsWith<IllegalStateException> {
            x = -1
        }
    }
}
