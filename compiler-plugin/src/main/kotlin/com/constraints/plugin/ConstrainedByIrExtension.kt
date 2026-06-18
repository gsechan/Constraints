package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val CONSTRAINED_BY_ANNOTATION = FqName("com.constraints.ConstrainedBy")

/** Backend hook: walks the IR and rewrites `@ConstrainedBy` assignments. */
class ConstrainedByIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(ConstrainedByIrTransformer(pluginContext), null)
    }
}

/**
 * Wraps every assignment to a `@ConstrainedBy`-annotated local variable in a
 * call to the user-supplied validator function. The FQN string in the
 * annotation is resolved to an IR symbol at compile time; if the function
 * cannot be uniquely resolved the annotation is skipped (with a stderr warning)
 * rather than crashing the compiler.
 */
class ConstrainedByIrTransformer(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoidWithContext() {

    // Initialisation:  @ConstrainedBy("pkg.fn") var x = <expr>  ->  ... = fn(<expr>)
    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer ?: return result
        val validator = result.resolveValidator() ?: return result
        result.initializer = wrap(initializer, validator)
        return result
    }

    // Reassignment:  x = <expr>  ->  x = fn(<expr>)
    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val target = result.symbol.owner as? IrVariable ?: return result
        val validator = target.resolveValidator() ?: return result
        result.value = wrap(result.value, validator)
        return result
    }

    /**
     * Reads the FQN string from `@ConstrainedBy`, parses it into a [CallableId],
     * and resolves it to a function symbol on the current classpath. Returns null
     * (and warns) if the annotation is absent or the function can't be uniquely
     * resolved, so unresolvable validators degrade gracefully.
     */
    private fun IrVariable.resolveValidator(): IrSimpleFunctionSymbol? {
        val annotation = getAnnotation(CONSTRAINED_BY_ANNOTATION) ?: return null
        val fqn = (annotation.getValueArgument(0) as? IrConst)?.value as? String ?: return null

        val candidates = pluginContext.referenceFunctions(fqn.toCallableId())
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> {
                System.err.println("constraints plugin: @ConstrainedBy validator '$fqn' not found on classpath — check skipped")
                null
            }
            else -> {
                System.err.println("constraints plugin: @ConstrainedBy validator '$fqn' is ambiguous (${candidates.size} overloads) — check skipped")
                null
            }
        }
    }

    private fun wrap(value: IrExpression, validator: IrSimpleFunctionSymbol): IrExpression {
        val scopeSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol, value.startOffset, value.endOffset)
        return builder.irCall(validator).apply {
            putValueArgument(0, value)
        }
    }
}

/**
 * Splits "com.example.myValidator" into package="com.example", name="myValidator".
 * Top-level functions in the default (empty) package fall back to FqName.ROOT.
 */
private fun String.toCallableId(): CallableId {
    val lastDot = lastIndexOf('.')
    return if (lastDot < 0) {
        CallableId(FqName.ROOT, Name.identifier(this))
    } else {
        CallableId(FqName(substring(0, lastDot)), Name.identifier(substring(lastDot + 1)))
    }
}
