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
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val INT_RANGE_ANNOTATION_FQ = FqName("com.constraints.IntRange")
private val CHECK_INT_RANGE_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkIntRange"))

/**
 * Fills in the bounds for the single-argument `checkIntRange(value)` escape hatch.
 *
 * The frontend accepts `@IntRange(min,max) x = checkIntRange(value)` (the 1-arg
 * form) but the runtime function has no bounds to check against, so this backend
 * pass rewrites the call to `checkIntRange(value, min, max)`, pulling `min`/`max`
 * from the `@IntRange` of the value being assigned to.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class CheckIntRangeBoundsIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val overloads = pluginContext.referenceFunctions(CHECK_INT_RANGE_CALLABLE)
        val oneArg = overloads.firstOrNull { it.owner.parameters.size == 1 } ?: return
        val threeArg = overloads.firstOrNull { it.owner.parameters.size == 3 } ?: return
        moduleFragment.transform(BoundsTransformer(pluginContext, oneArg, threeArg), null)
    }
}

@UnsafeDuringIrConstructionAPI
private class BoundsTransformer(
    private val pluginContext: IrPluginContext,
    private val oneArgCheck: IrSimpleFunctionSymbol,
    private val threeArgCheck: IrSimpleFunctionSymbol,
) : IrElementTransformerVoidWithContext() {

    // Initialisation:  @IntRange(0,10) var x = checkIntRange(v)  ->  ... = checkIntRange(v, 0, 10)
    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer
        val bounds = result.intRangeBounds()
        if (initializer != null && bounds != null) {
            result.initializer = fillBounds(initializer, bounds.first, bounds.second)
        }
        return result
    }

    // Reassignment:  x = checkIntRange(v)  ->  x = checkIntRange(v, 0, 10)
    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val bounds = (result.symbol.owner as? IrVariable)?.intRangeBounds()
        if (bounds != null) {
            result.value = fillBounds(result.value, bounds.first, bounds.second)
        }
        return result
    }

    /** Rewrites a bare `checkIntRange(value)` call into `checkIntRange(value, min, max)`. */
    private fun fillBounds(expr: IrExpression, min: Int, max: Int): IrExpression {
        if (expr !is IrCall || expr.symbol != oneArgCheck) return expr
        val value = expr.arguments[0] ?: return expr
        val scope = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scope, expr.startOffset, expr.endOffset)
        return builder.irCall(threeArgCheck).apply {
            arguments[0] = value
            arguments[1] = builder.irInt(min)
            arguments[2] = builder.irInt(max)
        }
    }

    private fun IrVariable.intRangeBounds(): Pair<Int, Int>? {
        val annotation: IrConstructorCall = getAnnotation(INT_RANGE_ANNOTATION_FQ) ?: return null
        val min = ((annotation.arguments[0] as? IrConst)?.value as? Number)?.toInt() ?: return null
        val max = ((annotation.arguments[1] as? IrConst)?.value as? Number)?.toInt() ?: return null
        return min to max
    }
}
