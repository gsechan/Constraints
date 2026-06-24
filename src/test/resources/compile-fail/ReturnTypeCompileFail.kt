package compile.fail

import com.constraints.IntRange

// Callee: body returns a value outside the declared return-type range.
fun badReturn(): @IntRange(0, 5) Int {
    return 9                               // ERROR: ranges do not overlap, so it can never be valid
}

// Caller: using a wider return type where a narrower one is required.
fun wide(): @IntRange(0, 10) Int = 7

fun test() {
    @IntRange(0, 5) val x = wide()        // ERROR: Cannot prove this satisfies @IntRange
}
