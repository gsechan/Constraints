package compile.fail

import com.constraints.LongDivisibleBy
import com.constraints.ShortDivisibleBy
import com.constraints.ByteDivisibleBy
import com.constraints.checkConstraint

fun test() {
    // LongDivisibleBy: literal has wrong residue.
    @LongDivisibleBy(2, 0) val a = 5L     // ERROR: can never be valid

    // ShortDivisibleBy: literal has wrong residue.
    @ShortDivisibleBy(2, 0) val b: Short = 5 // ERROR: can never be valid

    // ByteDivisibleBy: literal has wrong residue.
    @ByteDivisibleBy(2, 0) val c: Byte = 5   // ERROR: can never be valid

    // Zero divisors.
    @ShortDivisibleBy(0, 0) val d: Short = checkConstraint(4) // ERROR: divisor must be non-zero
    @ByteDivisibleBy(0, 0) val e: Byte = checkConstraint(4)   // ERROR: divisor must be non-zero
}
