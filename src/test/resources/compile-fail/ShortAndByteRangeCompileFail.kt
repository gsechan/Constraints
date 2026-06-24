package compile.fail

import com.constraints.ByteRange
import com.constraints.ShortRange

fun test() {
    // ByteRange: literal out of range.
    @ByteRange(0, 100) val a: Byte = 120   // ERROR: ranges do not overlap, so it can never be valid

    // ShortRange: literal out of range.
    @ShortRange(0, 100) val b: Short = 200 // ERROR: ranges do not overlap, so it can never be valid

    // Dynamic value -- cannot prove in range.
    val n: Short = 42
    @ShortRange(0, 10) val c: Short = n   // ERROR: Cannot prove this satisfies @ShortRange
}
