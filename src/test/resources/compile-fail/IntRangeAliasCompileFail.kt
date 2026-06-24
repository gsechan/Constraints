package compile.fail

import com.constraints.NegativeInt
import com.constraints.NonNegativeInt
import com.constraints.NonPositiveInt
import com.constraints.PositiveInt

fun test() {
    // PositiveInt requires [1, MAX] -- zero is out of range.
    @PositiveInt val a = 0                 // ERROR: can never be valid
    // PositiveInt requires [1, MAX] -- negative is out of range.
    @PositiveInt val b = -1                // ERROR: can never be valid

    // NegativeInt requires [MIN, -1] -- zero is out of range.
    @NegativeInt val c = 0                 // ERROR: can never be valid

    // NonPositiveInt requires [MIN, 0] -- positive is out of range.
    @NonPositiveInt val d = 1              // ERROR: can never be valid

    // NonNegativeInt requires [0, MAX] -- negative is out of range.
    @NonNegativeInt val e = -1             // ERROR: can never be valid
}
