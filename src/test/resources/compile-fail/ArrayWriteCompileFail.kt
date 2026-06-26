package compile.fail

import com.constraints.IntRange

fun dynamic(): Int = 0

fun test() {
    val array: Array<@IntRange(0, 10) Int> = arrayOf(0, 0, 0)

    // Provably-out-of-range write -> hard error.
    array[1] = -1            // ERROR: can never be valid

    // Unprovable write (a plain function result) -> needs checkConstraint.
    array[2] = dynamic()     // ERROR: Cannot prove this value satisfies @IntRange
}
