package compile.fail

import com.constraints.LongRange

fun test() {
    // Disjoint: literal is provably out of range.
    @LongRange(0, 100) val a = 200L        // ERROR: ranges do not overlap, so it can never be valid

    // Unknown: dynamic value cannot be proven in range.
    val n = System.currentTimeMillis()
    @LongRange(0, 100) val b = n           // ERROR: Cannot prove this satisfies @LongRange
}
