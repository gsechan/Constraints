package com.constraints.demo

import com.constraints.IntRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Modulo (`%`) interval inference. For `x % y` the result has the sign of the
 * dividend, has `|result| < |divisor|`, and is also bounded by the dividend itself.
 *
 * Each test below compiles only because the inferred range is provably within the
 * target `@IntRange`, so they run with **no** runtime check. Cases that must NOT
 * compile are documented at the bottom.
 */
class ModuloPluginTest {

    @Test
    fun `mod by 2 is 0 or 1`() {
        @IntRange(0, 10) var a = 7
        @IntRange(0, 1) var b = a % 2          // [0,10] % [2,2] = [0,1]
        assertEquals(1, b)
    }

    @Test
    fun `mod by 3 is 0 to 2`() {
        @IntRange(0, 10) var a = 8
        @IntRange(0, 2) var b = a % 3          // [0,10] % [3,3] = [0,2]
        assertEquals(2, b)
    }

    @Test
    fun `mod result fits a wider range`() {
        @IntRange(0, 10) var a = 9
        @IntRange(0, 10) var b = a % 4         // [0,3] is a subset of [0,10]
        assertEquals(1, b)
    }

    @Test
    fun `mod by a variable divisor`() {
        @IntRange(0, 10) var a = 10
        @IntRange(1, 5) var d = 4
        @IntRange(0, 4) var b = a % d          // divisor [1,5] -> |result| <= 4 -> [0,4]
        assertEquals(2, b)
    }

    @Test
    fun `mod keeps the sign of a negative dividend`() {
        @IntRange(-5, 5) var a = -4
        @IntRange(-2, 2) var b = a % 3         // [-5,5] % [3,3] = [-2,2]
        assertEquals(-1, b)
    }

    @Test
    fun `mod is bounded by the dividend when smaller than the divisor`() {
        @IntRange(0, 2) var a = 2
        @IntRange(0, 2) var b = a % 5          // a < 5, so a % 5 == a; result [0,2], tighter than |divisor|-1 = 4
        assertEquals(2, b)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //
    //   @IntRange(0, 10) var a = 9
    //   @IntRange(0, 0) var b = a % 3        // ERROR: a % 3 is [0,2], not within [0,0]
    //
    //   @IntRange(0, 10) var a = 9
    //   @IntRange(0, 10) var d = 5
    //   @IntRange(0, 5)  var b = a % d        // ERROR: d's range [0,10] includes 0 -> possible modulo by zero
    // -----------------------------------------------------------------------
}
