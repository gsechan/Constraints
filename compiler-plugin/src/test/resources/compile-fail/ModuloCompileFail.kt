package compile.fail

import com.constraints.IntRange

fun test() {
    // Result of modulo is [0,2], which is not a subset of [0,0].
    @IntRange(0, 10) var a = 9
    @IntRange(0, 0) var b = a % 3          // ERROR: Cannot prove this satisfies @IntRange

    // Divisor range includes 0 -> possible divide-by-zero.
    @IntRange(0, 10) var c = 9
    @IntRange(0, 10) var d = 5
    @IntRange(0, 5)  var e = c % d         // ERROR: Possible modulo by zero
}
