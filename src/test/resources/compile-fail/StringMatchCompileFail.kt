package compile.fail

import com.constraints.Matches
import com.constraints.Prefix
import com.constraints.Suffix
import com.constraints.checkConstraint

fun test() {
    // Literal provably violates the prefix -> hard error.
    @Prefix("foo") val a = "barfoo"        // ERROR: can never be valid

    // Literal provably violates the suffix.
    @Suffix("bar") val b = "barfoo"        // ERROR: can never be valid

    // Literal does not match the regex.
    @Matches("[0-9]+") val c = "abc"       // ERROR: can never be valid

    // Non-literal, not a known transfer -> needs checkConstraint.
    val dynamic = readLine() ?: ""
    @Prefix("foo") val d = dynamic         // ERROR: Cannot prove this satisfies @Prefix

    // Appending the prefix on the RIGHT of an unknown value doesn't establish it (only the left
    // operand of `+` carries the prefix).
    @Prefix("foo") val f = dynamic + "foo" // ERROR: Cannot prove this satisfies @Prefix

    // Transfer from a different prefix argument is not proven.
    @Prefix("foo") val src = checkConstraint("foobar")
    @Prefix("bar") val e = src             // ERROR: Cannot prove this satisfies @Prefix
}
