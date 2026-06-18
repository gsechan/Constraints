package com.constraints.plugin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory

/**
 * Compile-time diagnostics reported by the constraints plugin.
 *
 * [INTRANGE_NOT_VERIFIED] is an ERROR carrying one String argument (a
 * human-readable explanation). It fires when an `@IntRange` assignment cannot be
 * statically proven to lie within the declared range.
 */
object ConstraintErrors {
    val INTRANGE_NOT_VERIFIED by error1<PsiElement, String>()
}

/**
 * Registers the human-readable message for [ConstraintErrors]. The `init` block
 * installs it with the compiler's global renderer registry; the FIR extension
 * touches this object once so the registration runs.
 */
object ConstraintErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP = KtDiagnosticFactoryToRendererMap("Constraints").apply {
        put(ConstraintErrors.INTRANGE_NOT_VERIFIED, "{0}", CommonRenderers.STRING)
    }

    init {
        RootDiagnosticRendererFactory.registerFactory(this)
    }
}
