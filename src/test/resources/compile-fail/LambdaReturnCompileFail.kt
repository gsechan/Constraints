package compile.fail

import com.constraints.IntRange

class IntBox(val produce: () -> @IntRange(0, 10) Int)

fun test() {
    // Array constructor init returns a provably-out-of-range value -> hard error.
    val a: Array<@IntRange(0, 10) Int> = Array(5) { 100 }   // ERROR: can never be valid

    // Array constructor init is unprovable (depends on the index) -> needs checkConstraint(Array(...)).
    val b: Array<@IntRange(0, 10) Int> = Array(5) { it }    // ERROR: Cannot prove every element satisfies

    // Hand-annotated () -> @IntRange Int lambda whose return is provably out of range -> hard error.
    val box = IntBox { 100 }                                // ERROR: can never be valid

    // Hand-annotated lambda whose return is unprovable -> must be statically provable.
    val dynamic = listOf(1, 2, 3).first()
    val box2 = IntBox { dynamic }                           // ERROR: Cannot prove this lambda's return value
}
