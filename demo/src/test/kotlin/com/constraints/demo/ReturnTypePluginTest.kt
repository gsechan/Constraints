package com.constraints.demo

import com.constraints.IntRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A function whose RETURN TYPE is constrained with @IntRange. The plugin checks
 * the body honours it (callee side) and lets callers trust the result without a
 * runtime check (caller side).
 */
fun smallNumber(): @IntRange(0, 5) Int {
    return 3
}

class ReturnTypePluginTest {

    @Test
    fun `constrained return flows into a wider range`() {
        @IntRange(0, 10) var a = smallNumber()   // [0,5] is a subset of [0,10] -> proven, no check
        assertEquals(3, a)
    }

    @Test
    fun `constrained return flows into the same range`() {
        @IntRange(0, 5) val a = smallNumber()    // [0,5] is a subset of [0,5] -> proven
        assertEquals(3, a)
    }

    // -----------------------------------------------------------------------
    // These do NOT compile -- the constraint is enforced on both sides:
    //
    //   fun bad(): @IntRange(0, 5) Int { return 9 }    // ERROR (callee): 9 is not in [0,5]
    //
    //   fun wide(): @IntRange(0, 10) Int { return 7 }
    //   @IntRange(0, 5) val b = wide()                 // ERROR (caller): [0,10] not subset of [0,5]
    // -----------------------------------------------------------------------
}
