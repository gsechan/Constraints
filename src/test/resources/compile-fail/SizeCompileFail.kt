package compile.fail

import com.constraints.NonEmpty
import com.constraints.Size
import com.constraints.checkConstraint

fun test() {
    // CharSequence: literal length 5 not in [1, 3].
    @Size(1, 3) val s = "hello"                  // ERROR: the size ranges do not overlap

    // Collection: factory size 2 not in [5, 5].
    @Size(5, 5) val list = listOf(1, 2)          // ERROR: the size ranges do not overlap

    // Array constructor: size 6 not in [0, 5].
    @Size(0, 5) val arr = IntArray(6)            // ERROR: the size ranges do not overlap

    // Dynamic value (unknown size) assigned to a sized target.
    val items = listOf(1, 2, 3).filter { it > 0 }
    @Size(1, 5) val b = items                    // ERROR: Cannot prove this satisfies @Size

    // Transfer whose declared size [3, 3] doesn't overlap the target [5, 5].
    @Size(3, 3) val src = checkConstraint(listOf(1, 2, 3))
    @Size(5, 5) val c = src                      // ERROR: the size ranges do not overlap

    // NonEmpty alias ([1, MAX]): source is declared @Size(0, 0), no overlap.
    @Size(0, 0) val empty = checkConstraint(emptyList<Int>())
    @NonEmpty val d = empty                      // ERROR: the size ranges do not overlap
}
