package compile.fail

import com.constraints.IntRange
import com.constraints.Size

fun test() {
    // Array builder with a provably out-of-range element -> hard error on that element.
    val a: Array<@IntRange(0, 10) Int> = arrayOf(1, 20, 3)   // ERROR: can never be valid

    // Dynamic source, not a known transfer -> needs checkConstraint.
    val source = arrayOf(1, 2, 3).filter { it > 0 }.toTypedArray()
    val b: Array<@IntRange(0, 10) Int> = source              // ERROR: Cannot prove every element satisfies

    // Nested: an inner element provably out of range (depth 2) -> hard error.
    val c: Array<@Size(1, 1) Array<@IntRange(1, 10) Int>> = arrayOf(arrayOf(20)) // ERROR: can never be valid

    // Nested: an inner array provably the wrong size (depth 1) -> hard error.
    val d: Array<@Size(1, 1) Array<@IntRange(1, 10) Int>> = arrayOf(emptyArray()) // ERROR: can never be valid
}
