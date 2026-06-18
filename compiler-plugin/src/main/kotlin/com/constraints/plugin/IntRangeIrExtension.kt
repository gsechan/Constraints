package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val INT_RANGE_ANNOTATION = FqName("com.constraints.IntRange")
private val CHECK_INT_RANGE = CallableId(FqName("com.constraints"), Name.identifier("checkIntRange"))

/** Backend hook: walks the IR of every compiled file and rewrites `@IntRange` assignments. */
class IntRangeIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val checkIntRange = pluginContext.referenceFunctions(CHECK_INT_RANGE).singleOrNull() ?: return
        moduleFragment.transform(IntRangeIrTransformer(pluginContext, checkIntRange), null)
    }
}

/**
 * Wraps the right-hand side of any assignment to an `@IntRange`-annotated local
 * variable in a `checkIntRange(value, min, max)` call, so the constraint is
 * verified at runtime. The `min`/`max` bounds are read out of the annotation.
 */
class IntRangeIrTransformer(
    private val pluginContext: IrPluginContext,
    private val checkIntRange: IrSimpleFunctionSymbol,
) : IrElementTransformerVoidWithContext() {

    // Initialisation:  @IntRange(0,5) var x = <expr>  ->  ... = checkIntRange(<expr>, 0, 5)
    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer
        val bounds = result.intRangeBounds()
        if (initializer != null && bounds != null) {
            result.initializer = wrap(initializer, bounds.first, bounds.second)
        }
        return result
    }

    // Reassignment:   x = <expr>  ->  x = checkIntRange(<expr>, min, max)
    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val target = result.symbol.owner
        if (target is IrVariable) {
            val bounds = target.intRangeBounds()
            if (bounds != null) {
                result.value = wrap(result.value, bounds.first, bounds.second)
            }
        }
        return result
    }

    /** Reads the `(min, max)` literals from the `@IntRange` annotation, or null if absent. */
    private fun IrVariable.intRangeBounds(): Pair<Int, Int>? {
        val annotation: IrConstructorCall = getAnnotation(INT_RANGE_ANNOTATION) ?: return null
        val min = (annotation.arguments[0] as? IrConst)?.value as? Int ?: return null
        val max = (annotation.arguments[1] as? IrConst)?.value as? Int ?: return null
        return min to max
    }

    private fun wrap(value: IrExpression, min: Int, max: Int): IrExpression {
        val scopeSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol, value.startOffset, value.endOffset)
        return builder.irCall(checkIntRange).apply {
            arguments[0] = value
            arguments[1] = builder.irInt(min)
            arguments[2] = builder.irInt(max)
        }
    }
}
