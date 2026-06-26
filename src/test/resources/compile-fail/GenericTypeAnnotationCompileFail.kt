package compile.fail

import com.constraints.IntRange

// Return ranges used as builder elements below.
fun zeroOrOne(): @IntRange(0, 1) Int = 0     // overlaps [1,10] but isn't contained (could be 0)
fun farOut(): @IntRange(20, 30) Int = 25     // disjoint from [0,10]

fun test() {
    // Builder with a provably out-of-range element -> hard error on that element.
    val xs: List<@IntRange(0, 10) Int> = listOf(1, 20, 3)  // ERROR: can never be valid

    // Dynamic source, not a known transfer -> needs checkConstraint.
    val source = listOf(1, 2, 3).filter { it > 0 }
    val ys: List<@IntRange(0, 10) Int> = source            // ERROR: Cannot prove every element satisfies @IntRange

    // Transfer from a value WITHOUT the element-type constraint is not proven.
    val plain = listOf(4, 5)                               // List<Int>, no element constraint
    val zs: List<@IntRange(0, 10) Int> = plain             // ERROR: Cannot prove every element satisfies @IntRange

    // A function whose return range OVERLAPS but isn't contained (the original example: [0,1] into
    // [1,10] -- could be 0) -> undecidable, needs checkConstraint.
    val ws: List<@IntRange(1, 10) Int> = listOf(zeroOrOne()) // ERROR: Cannot prove every element satisfies @IntRange

    // A function whose return range is DISJOINT from the element constraint -> hard error.
    val vs: List<@IntRange(0, 10) Int> = listOf(farOut())  // ERROR: can never be valid
}
