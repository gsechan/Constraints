package compile.fail

import com.constraints.DoubleRange
import com.constraints.FloatRange

fun test() {
    // FloatRange: literal out of range.
    @FloatRange(0.0f, 1.0f) val a = 1.5f  // ERROR: ranges do not overlap, so it can never be valid

    // DoubleRange: literal out of range.
    @DoubleRange(0.0, 1.0) val b = 1.5    // ERROR: ranges do not overlap, so it can never be valid

    // Dynamic value -- cannot prove in range.
    val x = Math.random()
    @DoubleRange(0.0, 0.5) val c = x      // ERROR: Cannot prove this satisfies @DoubleRange
}
