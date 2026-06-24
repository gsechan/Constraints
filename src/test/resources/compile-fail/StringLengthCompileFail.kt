package compile.fail

import com.constraints.NonEmptyString
import com.constraints.StringLength
import com.constraints.checkConstraint

fun test() {
    // Literal length (5) is outside [1, 3] -- disjoint, hard error.
    @StringLength(1, 3) val a = "hello"   // ERROR: the length ranges do not overlap

    // Dynamic value -- length unknown at compile time.
    val s = readLine() ?: ""
    @StringLength(1, 5) val b = s         // ERROR: Cannot prove this satisfies @StringLength

    // NonEmptyString alias: empty string is length 0, outside [1, MAX].
    @NonEmptyString val c = ""            // ERROR: the length ranges do not overlap
}
