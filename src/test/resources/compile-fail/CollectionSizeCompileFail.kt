package compile.fail

import com.constraints.CollectionSize
import com.constraints.NonEmptyCollection
import com.constraints.checkConstraint

fun test() {
    // Transfer from a source whose declared size [3, 3] doesn't overlap the target [5, 5].
    // The checker reads the source variable's @CollectionSize annotation; no factory
    // inference needed.
    @CollectionSize(3, 3) val source = checkConstraint(listOf(1, 2, 3))
    @CollectionSize(5, 5) val a = source    // ERROR: the size ranges do not overlap

    // Dynamic value (filter result has unknown size at compile time) assigned to a
    // sized target -- cannot be proven.
    val items = listOf(1, 2, 3).filter { it > 0 }
    @CollectionSize(1, 5) val b = items     // ERROR: Cannot prove this satisfies @CollectionSize

    // NonEmptyCollection alias ([1, MAX]): source is declared @CollectionSize(0, 0),
    // which doesn't overlap [1, MAX].
    @CollectionSize(0, 0) val empty = checkConstraint(emptyList<Int>())
    @NonEmptyCollection val c = empty       // ERROR: the size ranges do not overlap
}
