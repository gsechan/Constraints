package compile.fail

import com.constraints.IntRange
import com.constraints.checkConstraint

fun test() {
    // Disjoint: literal is provably out of range -- hard error, no checkConstraint will help.
    @IntRange(0, 10) val a = 20             // ERROR: ranges do not overlap, so it can never be valid

    // Partially overlapping / unknown: the value is not provably in range -- soft error.
    val n = 42
    @IntRange(0, 10) val b = n              // ERROR: Cannot prove this satisfies @IntRange

    // Increment pushes a max-value variable out of range.
    @IntRange(0, 10) var c = 10; c++       // ERROR: Cannot prove this satisfies @IntRange

    // Return type: callee returns [0,5] Int but the caller wants [0,3].
    fun wide(): @IntRange(0, 5) Int = 3
    @IntRange(0, 3) val d = wide()         // ERROR: Cannot prove this satisfies @IntRange
}

// Return type: function body returns a value outside the declared return range.
fun badReturn(): @IntRange(0, 5) Int {
    return 9                               // ERROR: ranges do not overlap, so it can never be valid
}
