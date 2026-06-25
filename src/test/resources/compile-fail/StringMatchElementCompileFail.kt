package compile.fail

import com.constraints.Prefix

fun test() {
    // A literal element that provably lacks the prefix -> hard error on that element.
    val a: List<@Prefix("foo") String> = listOf("foobar", "nope") // ERROR: can never be valid

    // Dynamic source, not a known transfer -> needs checkConstraint.
    val source = listOf("foo1").filter { it.isNotEmpty() }
    val b: List<@Prefix("foo") String> = source // ERROR: Cannot prove every element satisfies
}
