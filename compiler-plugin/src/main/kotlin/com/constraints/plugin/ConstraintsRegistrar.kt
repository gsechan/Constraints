package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * Entry point the compiler discovers via
 * `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`.
 *
 * Registers:
 *  - @IntRange      -- STATIC compile-time checking via a FIR checker. An assignment
 *                      compiles only if the value is provably in range (an in-range
 *                      literal, a narrower @IntRange variable, interval-safe
 *                      arithmetic, or an explicit checkIntRange(...) call). Otherwise
 *                      it is a compile error -- no runtime check is injected.
 *  - @ConstrainedBy -- runtime validator resolved by fully-qualified name (unchanged).
 */
@OptIn(ExperimentalCompilerApi::class)
class ConstraintsComponentRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(IntRangeFirExtensionRegistrar())
        // Order matters: the escape-hatch rewrite must run BEFORE @ConstrainedBy so a
        // bare checkConstraint(value) is rewritten while it is still the top of the
        // assignment; @ConstrainedBy then wraps the result with its validator.
        IrGenerationExtension.registerExtension(CheckIntRangeBoundsIrGenerationExtension())
        IrGenerationExtension.registerExtension(ConstrainedByIrGenerationExtension())
    }
}
