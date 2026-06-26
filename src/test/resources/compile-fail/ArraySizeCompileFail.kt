package compile.fail

import com.constraints.ArraySize
import com.constraints.NonEmptyArray
import com.constraints.checkConstraint

fun test() {
    // Factory with a provably-wrong size: intArrayOf(1, 2) is size 2, target is [5, 5].
    @ArraySize(5, 5) val a = intArrayOf(1, 2)    // ERROR: the size ranges do not overlap

    // Constructor with a provably-wrong size: IntArray(6) is size 6, target is [0, 5].
    @ArraySize(0, 5) val ctor = IntArray(6)      // ERROR: the size ranges do not overlap

    // Transfer from a source whose declared size [3, 3] doesn't overlap the target [5, 5].
    @ArraySize(3, 3) val source = checkConstraint(intArrayOf(1, 2, 3))
    @ArraySize(5, 5) val b = source              // ERROR: the size ranges do not overlap

    // Dynamic value (filtered array has unknown size) assigned to a sized target.
    val items = intArrayOf(1, 2, 3).filter { it > 0 }.toIntArray()
    @ArraySize(1, 5) val c = items               // ERROR: Cannot prove this satisfies @ArraySize

    // NonEmptyArray alias ([1, MAX]): source is declared @ArraySize(0, 0), no overlap.
    @ArraySize(0, 0) val empty = checkConstraint(intArrayOf())
    @NonEmptyArray val d = empty                 // ERROR: the size ranges do not overlap
}
