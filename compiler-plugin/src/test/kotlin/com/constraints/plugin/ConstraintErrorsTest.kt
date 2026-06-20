package com.constraints.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * Smoke test for the diagnostics container. Forcing the renderer map guards against the
 * lazy-init regression we hit before: building the map eagerly read a diagnostic factory
 * during the container's own `<clinit>`, before its delegate field was set, which NPE'd.
 * The `by` (Lazy) delegate fixed it -- this test fails fast if that regresses.
 *
 * NOTE: this loads the Kotlin compiler's diagnostics infrastructure standalone. Verify in
 * IntelliJ -- it isn't runnable in the sandbox.
 */
class ConstraintErrorsTest {

    @Test
    fun `renderer map initializes without error`() {
        val map = ConstraintErrors.getRendererFactory().MAP
        assertNotNull(map)
    }
}
