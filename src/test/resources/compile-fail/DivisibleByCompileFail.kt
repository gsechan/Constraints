package compile.fail

import com.constraints.DivisibleBy
import com.constraints.IntRange
import com.constraints.Even
import com.constraints.Odd
import com.constraints.checkConstraint

fun test() {
    // Literal has wrong residue (known mismatch).
    @DivisibleBy(2, 0) val a = 5           // ERROR: can never be valid

    // Increment breaks divisibility.
    @DivisibleBy(2, 0) var b = 4; b++     // ERROR: Cannot prove this satisfies @DivisibleBy

    // Combined: IntRange fails (12 > 10).
    @IntRange(0, 10) @DivisibleBy(2, 0) val c = 12 // ERROR: can never be valid

    // Combined: DivisibleBy fails (5 is odd).
    @IntRange(0, 10) @DivisibleBy(2, 0) val d = 5  // ERROR: can never be valid

    // Alias: 4 is even, so @Odd fails.
    @Odd val e = 4                         // ERROR: can never be valid

    // Zero divisor at a use site.
    @DivisibleBy(0, 0) val f = checkConstraint(5) // ERROR: divisor must be non-zero
}

// Zero divisor on an alias annotation definition.
@DivisibleBy(0)                            // ERROR: divisor must be non-zero
@Target(AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.SOURCE)
annotation class BadDivisor
