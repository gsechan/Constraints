package com.constraints.plugin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

/**
 * Compile-time diagnostics reported by the constraints plugin.
 *
 * Each is an ERROR carrying one String argument -- the human-readable explanation
 * passed at the report site:
 *  - [INTRANGE_NOT_VERIFIED]   -- an `@IntRange` assignment can't be statically proven
 *                                 to lie within the declared range.
 *  - [INTRANGE_DIVISION_BY_ZERO] -- an integer division/modulo whose divisor range includes 0.
 *  - [CONSTRAINT_NOT_VALIDATED] -- an assignment to a runtime-only `@ConstrainedBy` value
 *                                 that isn't wrapped in `checkConstraint(value)`.
 *
 * Declared on a [KtDiagnosticsContainer] (the context `error1` needs) and wired
 * into the compiler via `registerDiagnosticContainers(...)` in the FIR registrar.
 * [getRendererFactory] supplies the message template ("{0}" = the String arg).
 */
object ConstraintErrors : KtDiagnosticsContainer() {
    val INTRANGE_NOT_VERIFIED by error1<PsiElement, String>()
    val INTRANGE_DIVISION_BY_ZERO by error1<PsiElement, String>()
    val CONSTRAINT_NOT_VALIDATED by error1<PsiElement, String>()
    val DIVISIBLE_BY_NOT_VERIFIED by error1<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = ConstraintErrorRenderers
}

private object ConstraintErrorRenderers : BaseDiagnosticRendererFactory() {
    // Use `by` (a Lazy delegate), NOT `.value`: forcing the map eagerly reads
    // ConstraintErrors.INTRANGE_NOT_VERIFIED during the container's own
    // initialization -- before that delegate field is set -- which NPEs. `by`
    // defers building the map until first render, when init is complete.
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Constraints") {
        it.put(ConstraintErrors.INTRANGE_NOT_VERIFIED, "{0}", CommonRenderers.STRING)
        it.put(ConstraintErrors.INTRANGE_DIVISION_BY_ZERO, "{0}", CommonRenderers.STRING)
        it.put(ConstraintErrors.CONSTRAINT_NOT_VALIDATED, "{0}", CommonRenderers.STRING)
        it.put(ConstraintErrors.DIVISIBLE_BY_NOT_VERIFIED, "{0}", CommonRenderers.STRING)
    }
}