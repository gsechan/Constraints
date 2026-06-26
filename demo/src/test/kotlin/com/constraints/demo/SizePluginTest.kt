package com.constraints.demo

import com.constraints.ConstraintException
import com.constraints.NonEmpty
import com.constraints.Size
import com.constraints.checkConstraint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @Size unifies the former @StringLength / @CollectionSize / @ArraySize: it bounds the size of a
 * CharSequence (length), a Collection/Map, or an array (Array<T> and the primitive aliases).
 */
class SizePluginTest {

    // === CharSequence (length) ===

    @Test
    fun `string literal whose length is in range compiles`() {
        @Size(1, 10) val s = "hello"
        assertEquals("hello", s)
    }

    @Test
    fun `string concatenation of known-length strings compiles`() {
        @Size(2, 5) val a = checkConstraint("hi")
        @Size(3, 7) val b = checkConstraint("bye")
        @Size(5, 12) val c = a + b   // [2,5]+[3,7] = [5,12]
        assertEquals("hibye", c)
    }

    @Test
    fun `string transfer from narrower range compiles`() {
        @Size(3, 5) val a = checkConstraint("hi!")
        @Size(1, 10) val b = a       // [3,5] ⊆ [1,10]
        assertEquals("hi!", b)
    }

    @Test
    fun `checkConstraint throws for string that is too long`() {
        assertFailsWith<ConstraintException> {
            @Size(1, 3) val s = checkConstraint("hello")
            println(s)
        }
    }

    // === Collection / Map ===

    @Test
    fun `listOf with known args compiles`() {
        @Size(3, 3) val list = listOf(1, 2, 3)
        assertEquals(3, list.size)
    }

    @Test
    fun `mapOf with known args compiles`() {
        @Size(2, 2) val map = mapOf("a" to 1, "b" to 2)
        assertEquals(2, map.size)
    }

    @Test
    fun `adding a single element increments collection size by one`() {
        @Size(3, 3) val list = listOf(1, 2, 3)
        @Size(4, 4) val grown = list + 4
        assertEquals(4, grown.size)
    }

    @Test
    fun `removing a single element reduces size by at most one`() {
        @Size(3, 3) val list = listOf(1, 2, 3)
        @Size(2, 3) val shrunk = list - 1   // removes first occurrence → [2,3]
        assertEquals(2, shrunk.size)
    }

    @Test
    fun `collection transfer from narrower size compiles`() {
        @Size(3, 3) val a = listOf(1, 2, 3)
        @Size(1, 5) val b = a
        assertEquals(3, b.size)
    }

    @Test
    fun `checkConstraint throws for collection that is too large`() {
        assertFailsWith<ConstraintException> {
            @Size(1, 2) val list = checkConstraint(listOf(1, 2, 3, 4))
            println(list)
        }
    }

    @Test
    fun `checkConstraint validates a map at runtime`() {
        @Size(1, 5) val map = checkConstraint(mapOf("a" to 1, "b" to 2))
        assertEquals(2, map.size)
    }

    @Test
    fun `checkConstraint throws for a map that is too large`() {
        assertFailsWith<ConstraintException> {
            @Size(0, 1) val map = checkConstraint(mapOf("a" to 1, "b" to 2))
            println(map)
        }
    }

    // === Arrays ===

    @Test
    fun `arrayOf with known args compiles`() {
        @Size(3, 3) val arr = arrayOf("a", "b", "c")
        assertEquals(3, arr.size)
    }

    @Test
    fun `intArrayOf with known args compiles`() {
        @Size(3, 3) val arr = intArrayOf(1, 2, 3)
        assertEquals(3, arr.size)
    }

    @Test
    fun `IntArray constructor with a literal size compiles`() {
        @Size(6, 6) val arr = IntArray(6)
        assertEquals(6, arr.size)
    }

    @Test
    fun `adding two known-size arrays compiles`() {
        @Size(2, 2) val a = intArrayOf(1, 2)
        @Size(3, 3) val b = intArrayOf(3, 4, 5)
        @Size(5, 5) val c = a + b
        assertEquals(5, c.size)
    }

    @Test
    fun `array transfer from narrower size compiles`() {
        @Size(3, 3) val a = intArrayOf(1, 2, 3)
        @Size(1, 5) val b = a
        assertEquals(3, b.size)
    }

    @Test
    fun `checkConstraint throws for array that is too large`() {
        assertFailsWith<ConstraintException> {
            @Size(1, 2) val arr = checkConstraint(intArrayOf(1, 2, 3, 4))
            println(arr)
        }
    }

    // === @NonEmpty alias (works across all sized types) ===

    @Test
    fun `NonEmpty accepts a non-empty string`() {
        @NonEmpty val s = "hello"
        assertEquals("hello", s)
    }

    @Test
    fun `NonEmpty accepts a non-empty list`() {
        @NonEmpty val list = listOf(1, 2, 3)
        assertEquals(3, list.size)
    }

    @Test
    fun `NonEmpty accepts a non-empty array`() {
        @NonEmpty val arr = intArrayOf(1, 2, 3)
        assertEquals(3, arr.size)
    }

    @Test
    fun `NonEmpty checkConstraint throws for empty collection`() {
        assertFailsWith<ConstraintException> {
            @NonEmpty val list = checkConstraint(emptyList<Int>())
            println(list)
        }
    }

    // === As an element-type constraint ===

    @Test
    fun `@Size works as a list element-type constraint`() {
        val rows: List<@Size(2, 2) IntArray> = checkConstraint(listOf(intArrayOf(1, 2), intArrayOf(3, 4)))
        assertEquals(2, rows.size)
    }

    @Test
    fun `@Size element-type constraint throws when an element is the wrong size`() {
        assertFailsWith<ConstraintException> {
            val names: List<@Size(1, 3) String> = checkConstraint(listOf("ok", "toolong"))
            println(names)
        }
    }

    // -----------------------------------------------------------------------
    // These do NOT compile (see SizeCompileFail.kt):
    //   @Size(1, 3) val x = "hello"            // length 5 not in [1, 3]
    //   @Size(5, 5) val y = listOf(1, 2)       // size 2 not in [5, 5]
    //   @Size(0, 5) val z = IntArray(6)        // constructor size 6 not in [0, 5]
    //   @Size(1, 5) val w = userInput          // size unknown: needs checkConstraint
    //   @NonEmpty   val e = ""                 // size 0 not in [1, MAX_VALUE]
    // -----------------------------------------------------------------------
}
