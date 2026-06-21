package com.constraints.demo

import com.constraints.Constraint
import com.constraints.ConstraintException
import com.constraints.IntRange
import com.constraints.Validator
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A function whose RETURN TYPE is constrained with @IntRange. The plugin checks
 * the body honours it (callee side) and lets callers trust the result without a
 * runtime check (caller side).
 */
fun smallNumber(): @IntRange(0, 5) Int {
    return 3
}

// A custom (runtime-only) constraint usable on a return type -- note AnnotationTarget.TYPE.
@Constraint(PosValidator::class)
@Target(AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class Pos

object PosValidator : Validator<Int, Pos> {
    override fun validate(value: Int, annotation: Pos) {
        if (value <= 0) throw ConstraintException("must be positive, got $value")
    }
}

// The body returns a value already known to satisfy @Pos, so the return checker proves it by
// transfer -- no runtime check is injected on the return.
fun positiveFromLocal(): @Pos Int {
    @Pos val x = checkConstraint(5)
    return x
}

class ReturnTypePluginTest {

    @Test
    fun `constrained return flows into a wider range`() {
        @IntRange(0, 10) var a = smallNumber()   // [0,5] is a subset of [0,10] -> proven, no check
        assertEquals(3, a)
    }

    @Test
    fun `constrained return flows into the same range`() {
        @IntRange(0, 5) val a = smallNumber()    // [0,5] is a subset of [0,5] -> proven
        assertEquals(3, a)
    }

    @Test
    fun `custom constraint return proven by transfer`() {
        // positiveFromLocal() returns a known @Pos value, so the return checker accepts it.
        assertEquals(5, positiveFromLocal())
    }

    // -----------------------------------------------------------------------
    // These do NOT compile -- the constraint is enforced on both sides:
    //
    //   fun bad(): @IntRange(0, 5) Int { return 9 }    // ERROR (callee): 9 is not in [0,5]
    //
    //   fun wide(): @IntRange(0, 10) Int { return 7 }
    //   @IntRange(0, 5) val b = wide()                 // ERROR (caller): [0,10] not subset of [0,5]
    //
    //   fun badPos(): @Pos Int { return 5 }            // ERROR (callee): 5 isn't a known @Pos value;
    //                                                  // needs checkConstraint or a @Pos source
    // -----------------------------------------------------------------------
}
