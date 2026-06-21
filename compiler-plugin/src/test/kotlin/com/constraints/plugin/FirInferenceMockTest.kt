package com.constraints.plugin

import io.mockk.every
import io.mockk.mockk
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MockK-based tests for the thin FIR-adapter layer -- the functions that translate FIR nodes
 * into the pure lattice/residue logic. Only the leaf cases (literals, the unknown fallback) are
 * covered here, because they need a single mocked property; deeper cases (arithmetic calls,
 * variable reads, annotation reading) require mocking resolved symbols and extension functions
 * over a whole FIR subtree, which is brittle against compiler-version churn and is instead
 * exercised end-to-end by the demo integration tests.
 *
 * NOTE: these mock the Kotlin compiler's FIR API, which changes between versions. If a Kotlin
 * upgrade breaks them, fix the mock setup to match the new FIR shape (or rely on the demo
 * integration tests for that path). Verify in IntelliJ -- they aren't runnable in the sandbox.
 */
class FirInferenceMockTest {

    private val session = mockk<FirSession>(relaxed = true)

    private fun intLiteral(literalValue: Long): FirLiteralExpression {
        val mock = mockk<FirLiteralExpression>()
        every { mock.value } returns literalValue
        return mock
    }

    @Test
    fun `inferInterval reads an integer literal as a point interval`() {
        assertEquals(Interval.point(7), inferInterval(intLiteral(7), session, NumericDomain.INT))
    }

    @Test
    fun `inferInterval is UNKNOWN for an absent expression`() {
        assertEquals(Interval.UNKNOWN, inferInterval(null, session, NumericDomain.INT))
    }

    @Test
    fun `inferResidue reduces a literal modulo the divisor (floored)`() {
        assertEquals(1L, inferRemainder(intLiteral(7), 3L, session)) // 7 mod 3 == 1
        assertEquals(2L, inferRemainder(intLiteral(-1), 3L, session)) // floored: -1 mod 3 == 2
    }

    @Test
    fun `inferResidue is null for a zero divisor or absent expression`() {
        assertNull(inferRemainder(intLiteral(7), 0L, session))
        assertNull(inferRemainder(null, 3L, session))
    }

    @Test
    fun `comparableValue returns a literal's raw value`() {
        assertEquals(5L, comparableValue(intLiteral(5)))
    }
}
