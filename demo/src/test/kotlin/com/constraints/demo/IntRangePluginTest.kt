package com.constraints.demo

import com.constraints.IntRange
import com.constraints.checkIntRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@IntRange` is now enforced at COMPILE TIME. An assignment compiles only if the
 * value is *provably* in range; otherwise it is a compile error and no runtime
 * check is injected. The provably-safe cases below therefore run with zero
 * runtime cost. The cases that must NOT compile are listed (commented) at the end
 * -- including the `a++` example this change was built for.
 */
class IntRangePluginTest {

    @Test
    fun `in-range literal compiles and holds`() {
        @IntRange(0, 10) var a = 9
        assertEquals(9, a)
    }

    @Test
    fun `narrower range is assignable to wider (subsumption)`() {
        @IntRange(0, 5) var a = 3
        @IntRange(0, 10) var b = a            // [0,5] is a subset of [0,10] -> proven, no check
        assertEquals(3, b)
    }

    @Test
    fun `interval-safe arithmetic compiles`() {
        @IntRange(0, 4) var a = 2
        @IntRange(0, 10) var b = a + a        // [0,4] + [0,4] = [0,8] is a subset of [0,10] -> proven
        assertEquals(4, b)
    }

    @Test
    fun `dynamic value via checkIntRange compiles`() {
        val raw = 7
        @IntRange(0, 10) var a = checkIntRange(raw, 0, 10)   // escape hatch: checked at runtime
        assertEquals(7, a)
    }

    @Test
    fun `checkIntRange throws at runtime when out of range`() {
        assertFailsWith<IllegalStateException> {
            @IntRange(0, 10) var a = checkIntRange(42, 0, 10)
            println(a)
        }
    }

    // -----------------------------------------------------------------------
    // The following do NOT compile -- that is the whole point of moving the
    // check to compile time. Uncommenting any of them is a compile error.
    //
    //   @IntRange(0, 10) var a = 9
    //   a++                          // ERROR: a is [0,10], so a + 1 is [1,11], not subset of [0,10]
    //
    //   @IntRange(0, 10) var b = 20  // ERROR: literal 20 is not in [0,10]
    //
    //   val n = readLine()!!.toInt()
    //   @IntRange(0, 10) var c = n   // ERROR: the range of n cannot be determined statically
    //
    // The fix in each case is an explicit runtime check, e.g.:
    //   a = checkIntRange(a + 1, 0, 10)
    // -----------------------------------------------------------------------
}
