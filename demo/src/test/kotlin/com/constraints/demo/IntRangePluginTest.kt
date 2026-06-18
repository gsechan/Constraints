package com.constraints.demo

import com.constraints.IntRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntRangePluginTest {

    @Test
    fun `value within range is allowed`() {
        @IntRange(0, 5) var x = 3
        assertEquals(3, x)
    }

    @Test
    fun `min boundary is allowed`() {
        @IntRange(0, 5) var x = 0
        assertEquals(0, x)
    }

    @Test
    fun `max boundary is allowed`() {
        @IntRange(0, 5) var x = 5
        assertEquals(5, x)
    }

    @Test
    fun `below min on init throws`() {
        assertFailsWith<IllegalStateException> {
            @IntRange(0, 5) var x = -1
            println(x) // never reached
        }
    }

    @Test
    fun `above max on init throws`() {
        assertFailsWith<IllegalStateException> {
            @IntRange(0, 5) var x = 6
            println(x) // never reached
        }
    }

    @Test
    fun `reassigning within range is allowed`() {
        @IntRange(0, 5) var x = 0
        x = 4
        assertEquals(4, x)
    }

    @Test
    fun `reassigning out of range throws`() {
        @IntRange(0, 5) var x = 0
        assertFailsWith<IllegalStateException> {
            x = 99
        }
    }
}
