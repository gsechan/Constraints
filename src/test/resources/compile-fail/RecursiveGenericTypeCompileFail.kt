package compile.fail

import com.constraints.Size
import com.constraints.IntRange

fun test() {
    // Inner element provably out of [2, 5] (depth 2) -> hard error on that element.
    val a: List<@Size(1, 10) List<@IntRange(2, 5) Int>> = listOf(listOf(20)) // ERROR: can never be valid

    // Inner collection provably the wrong size (0 not in [1, 10], depth 1) -> hard error.
    val b: List<@Size(1, 10) List<@IntRange(2, 5) Int>> = listOf(emptyList()) // ERROR: can never be valid

    // Dynamic nested source, not a known transfer -> needs checkConstraint.
    val source = listOf(listOf(3)).filter { it.isNotEmpty() }
    val c: List<@Size(1, 10) List<@IntRange(2, 5) Int>> = source // ERROR: Cannot prove every element satisfies

    // Transfer from a value WITHOUT the nested element-type constraints is not proven.
    val plain = listOf(listOf(3))
    val d: List<@Size(1, 10) List<@IntRange(2, 5) Int>> = plain // ERROR: Cannot prove every element satisfies
}
