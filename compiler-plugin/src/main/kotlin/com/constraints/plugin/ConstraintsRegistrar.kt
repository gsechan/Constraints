package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Entry point the compiler discovers via
 * `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`.
 *
 * Registers the two runtime-enforcement extensions:
 *  - @IntRange      -- built-in range check (reads min/max from the annotation)
 *  - @ConstrainedBy -- user-supplied validator resolved by fully-qualified name
 *
 * Both currently inject runtime checks. Static (compile-time) checking for
 * @IntRange is the next layer and will be added as a FIR checker extension here.
 */
@OptIn(ExperimentalCompilerApi::class)
class ConstraintsComponentRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(IntRangeIrGenerationExtension())
        IrGenerationExtension.registerExtension(ConstrainedByIrGenerationExtension())
    }
}
