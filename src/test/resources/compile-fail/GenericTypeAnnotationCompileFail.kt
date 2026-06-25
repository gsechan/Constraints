package compile.fail

import com.constraints.IntRange

fun test() {
    // Element-type constraint with no checkConstraint -> can't prove every element is in range.
    val xs: List<@IntRange(0, 10) Int> = listOf(1, 2, 3)   // ERROR: Cannot prove every element satisfies @IntRange

    // Dynamic source, not a known transfer -> needs checkConstraint.
    val source = listOf(1, 2, 3).filter { it > 0 }
    val ys: List<@IntRange(0, 10) Int> = source            // ERROR: Cannot prove every element satisfies @IntRange

    // Transfer from a value WITHOUT the element-type constraint is not proven.
    val plain = listOf(4, 5)                               // List<Int>, no element constraint
    val zs: List<@IntRange(0, 10) Int> = plain             // ERROR: Cannot prove every element satisfies @IntRange
}
