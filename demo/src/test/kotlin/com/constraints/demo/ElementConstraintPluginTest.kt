package com.constraints.demo

import com.constraints.Size
import com.constraints.ConstraintException
import com.constraints.ElementConstraint
import com.constraints.Validator
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------------------------------------------------------------------------
// @ElementConstraint: a meta-annotation that links a constraint annotation to a
// validator that is applied to each *element* of the annotated collection, rather
// than to the collection itself. Throw-on-first semantics.
//
// Static analysis: element constraints are opaque (we can't inspect what's inside a
// collection at compile time), so the only static proof is a TRANSFER -- assigning
// from a value already declared with the same element constraint. Anything else
// requires checkConstraint(value), which runs the element validator for each element.
// ---------------------------------------------------------------------------

@ElementConstraint(PositiveElementValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class AllPositive

object PositiveElementValidator : Validator<Int, AllPositive> {
    override fun validate(value: Int, annotation: AllPositive) {
        if (value <= 0) throw ConstraintException("All elements must be positive; got $value")
    }
}

// An annotation that combines both a collection-level and an element-level constraint.
@Size(1, Int.MAX_VALUE)
@ElementConstraint(NonNegativeElementValidator::class)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class NonEmptyAllNonNegative

object NonNegativeElementValidator : Validator<Int, NonEmptyAllNonNegative> {
    override fun validate(value: Int, annotation: NonEmptyAllNonNegative) {
        if (value < 0) throw ConstraintException("All elements must be non-negative; got $value")
    }
}

class ElementConstraintPluginTest {

    @Test
    fun `element validator passes for all-valid collection`() {
        @AllPositive val list = checkConstraint(listOf(1, 2, 3))
        assertEquals(3, list.size)
    }

    @Test
    fun `element validator throws on first invalid element`() {
        assertFailsWith<ConstraintException> {
            @AllPositive val list = checkConstraint(listOf(1, -2, 3))
            println(list)
        }
    }

    @Test
    fun `element validator throws when zero is present`() {
        assertFailsWith<ConstraintException> {
            @AllPositive val list = checkConstraint(listOf(1, 0, 3))
            println(list)
        }
    }

    @Test
    fun `transfer between same element-constrained values compiles`() {
        // `a` had its elements validated at runtime; `b = a` is proven at compile time because
        // `a` is already known to have @AllPositive elements -- no re-check injected.
        @AllPositive val a = checkConstraint(listOf(1, 2, 3))
        @AllPositive val b = a
        assertEquals(listOf(1, 2, 3), b)
    }

    // --- Arrays: @ElementConstraint applies element-by-element to arrays too ---

    @Test
    fun `element validator passes for all-valid primitive array`() {
        @AllPositive val arr = checkConstraint(intArrayOf(1, 2, 3))
        assertEquals(3, arr.size)
    }

    @Test
    fun `element validator throws on first invalid primitive-array element`() {
        assertFailsWith<ConstraintException> {
            @AllPositive val arr = checkConstraint(intArrayOf(1, -2, 3))
            println(arr)
        }
    }

    @Test
    fun `element validator passes for all-valid object array`() {
        @AllPositive val arr = checkConstraint(arrayOf(1, 2, 3))
        assertEquals(3, arr.size)
    }

    @Test
    fun `element validator throws on first invalid object-array element`() {
        assertFailsWith<ConstraintException> {
            @AllPositive val arr = checkConstraint(arrayOf(1, 2, -3))
            println(arr)
        }
    }

    @Test
    fun `transfer between same element-constrained arrays compiles`() {
        @AllPositive val a = checkConstraint(intArrayOf(1, 2, 3))
        @AllPositive val b = a   // proven by transfer; no re-check injected
        assertEquals(3, b.size)
    }

    @Test
    fun `combined collection-level and element-level constraints both checked`() {
        // Size(1, MAX) checked first (collection-level), then each element (element-level).
        @NonEmptyAllNonNegative val list = checkConstraint(listOf(0, 1, 2))
        assertEquals(3, list.size)
    }

    @Test
    fun `combined constraint throws when collection is empty`() {
        assertFailsWith<ConstraintException> {
            @NonEmptyAllNonNegative val list = checkConstraint(emptyList<Int>())
            println(list)
        }
    }

    @Test
    fun `combined constraint throws when an element is negative`() {
        assertFailsWith<ConstraintException> {
            @NonEmptyAllNonNegative val list = checkConstraint(listOf(1, -1, 3))
            println(list)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @AllPositive val x = listOf(1, 2, 3)           // no checkConstraint: can't prove elements
    //   @AllPositive val z = someOtherList              // unknown elements: needs checkConstraint
    //   @AllNegative val y = allPositiveList            // different element constraint: not proven
    // -----------------------------------------------------------------------
}
