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
 *  - @ConstrainedBy -- runtime-only validator. Its result can never be proven statically,
 *                      so the FIR checker rejects every assignment except a bare
 *                      checkConstraint(value); the IR backend injects the validator call
 *                      into that escape hatch (same pass that handles @IntRange's).
 */
@OptIn(ExperimentalCompilerApi::class)
class ConstraintsComponentRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(IntRangeFirExtensionRegistrar())
        // The escape-hatch rewrite injects validator calls for both @IntRange (compile-time)
        // and @ConstrainedBy (runtime-only) constraints into checkConstraint(value)/checkIntRange(value).
        IrGenerationExtension.registerExtension(CheckIntRangeBoundsIrGenerationExtension())
    }
}
