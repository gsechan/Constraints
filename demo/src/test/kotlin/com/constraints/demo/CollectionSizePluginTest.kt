package com.constraints.demo

import com.constraints.CollectionSize
import com.constraints.ConstraintException
import com.constraints.NonEmptyCollection
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollectionSizePluginTest {

    // --- Factory functions ---

    @Test
    fun `emptyList has size zero`() {
        @CollectionSize(0, 0) val list = emptyList<Int>()
        assertEquals(0, list.size)
    }

    @Test
    fun `listOf with known args compiles`() {
        @CollectionSize(3, 3) val list = listOf(1, 2, 3)
        assertEquals(3, list.size)
    }

    @Test
    fun `setOf with known args compiles`() {
        @CollectionSize(2, 2) val set = setOf("a", "b")
        assertEquals(2, set.size)
    }

    @Test
    fun `mapOf with known args compiles`() {
        @CollectionSize(2, 2) val map = mapOf("a" to 1, "b" to 2)
        assertEquals(2, map.size)
    }

    // --- Addition: element vs collection distinction ---

    @Test
    fun `adding a single element increments size by exactly one`() {
        @CollectionSize(3, 3) val list = listOf(1, 2, 3)
        @CollectionSize(4, 4) val grown = list + 4   // single element → always +1, proven exactly
        assertEquals(4, grown.size)
    }

    @Test
    fun `adding a single element to a bounded list compiles`() {
        @CollectionSize(1, 5) val list = checkConstraint(listOf(1, 2))
        @CollectionSize(2, 6) val grown = list + 3   // [1,5] + 1 = [2,6]
        assertEquals(3, grown.size)
    }

    @Test
    fun `adding two known-size lists compiles`() {
        @CollectionSize(2, 2) val a = listOf(1, 2)
        @CollectionSize(3, 3) val b = listOf(3, 4, 5)
        @CollectionSize(5, 5) val c = a + b   // [2,2]+[3,3] = [5,5]
        assertEquals(5, c.size)
    }

    @Test
    fun `adding two bounded lists compiles`() {
        @CollectionSize(1, 3) val a = checkConstraint(listOf(1))
        @CollectionSize(2, 4) val b = checkConstraint(listOf(2, 3))
        @CollectionSize(3, 7) val c = a + b   // [1,3]+[2,4] = [3,7]
        assertEquals(3, c.size)
    }

    // --- Subtraction: element vs collection distinction ---

    @Test
    fun `removing a single element reduces size by at most one`() {
        @CollectionSize(3, 3) val list = listOf(1, 2, 3)
        // Kotlin's minus removes only the FIRST occurrence → 0 or 1 removed → [2, 3]
        @CollectionSize(2, 3) val shrunk = list - 1
        assertEquals(2, shrunk.size)
    }

    @Test
    fun `removing an element that is absent leaves size unchanged`() {
        @CollectionSize(3, 3) val list = listOf(1, 2, 3)
        @CollectionSize(2, 3) val result = list - 99   // 99 not present → size still 3, within [2,3]
        assertEquals(3, result.size)
    }

    @Test
    fun `removing a known-size collection produces a bounded result`() {
        @CollectionSize(5, 5) val base = listOf(1, 2, 3, 4, 5)
        @CollectionSize(2, 2) val toRemove = listOf(1, 2)
        // [5,5] - [2,2] → min = max(0, 5-2) = 3, max = 5 → [3, 5]
        @CollectionSize(3, 5) val result = base - toRemove
        assertEquals(3, result.size)
    }

    // --- Transfer ---

    @Test
    fun `transfer between same-size collections compiles`() {
        @CollectionSize(3, 3) val a = listOf(1, 2, 3)
        @CollectionSize(3, 3) val b = a
        assertEquals(3, b.size)
    }

    @Test
    fun `transfer from narrower size compiles`() {
        @CollectionSize(3, 3) val a = listOf(1, 2, 3)
        @CollectionSize(1, 5) val b = a   // [3,3] ⊆ [1,5]
        assertEquals(3, b.size)
    }

    // --- Runtime escape hatch ---

    @Test
    fun `checkConstraint passes for collection in range`() {
        @CollectionSize(1, 5) val list = checkConstraint(listOf("a", "b"))
        assertEquals(2, list.size)
    }

    @Test
    fun `checkConstraint throws for collection that is too large`() {
        assertFailsWith<ConstraintException> {
            @CollectionSize(1, 2) val list = checkConstraint(listOf(1, 2, 3, 4))
            println(list)
        }
    }

    @Test
    fun `checkConstraint throws for empty collection when min is one`() {
        assertFailsWith<ConstraintException> {
            @CollectionSize(1, 10) val list = checkConstraint(emptyList<String>())
            println(list)
        }
    }

    // --- @NonEmptyCollection alias ---

    @Test
    fun `NonEmptyCollection alias accepts a non-empty factory result`() {
        @NonEmptyCollection val list = listOf(1, 2, 3)
        assertEquals(3, list.size)
    }

    @Test
    fun `NonEmptyCollection adding an element to empty stays non-empty`() {
        @NonEmptyCollection val list = emptyList<Int>() + 1  // [0,0]+1 = [1,1] ⊆ [1,MAX]
        assertEquals(1, list.size)
    }

    @Test
    fun `NonEmptyCollection checkConstraint throws for empty collection`() {
        assertFailsWith<ConstraintException> {
            @NonEmptyCollection val list = checkConstraint(emptyList<Int>())
            println(list)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile:
    //   @CollectionSize(5, 5) val x = listOf(1, 2)   // size 2 not in [5, 5]
    //   @CollectionSize(1, 5) val y = someCollection  // size unknown: needs checkConstraint
    //   @CollectionSize(4, 4) val z = list + otherList // both need @CollectionSize to prove
    //   @NonEmptyCollection val w = emptyList()       // size 0 not in [1, MAX_VALUE]
    // -----------------------------------------------------------------------
}
