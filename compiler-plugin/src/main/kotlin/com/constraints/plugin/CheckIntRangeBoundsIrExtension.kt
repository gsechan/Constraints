package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val COMPILE_TIME_CONSTRAINT_FQ = FqName("com.constraints.CompileTimeConstraint")
private val CHECK_INT_RANGE_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkIntRange"))
private val CHECK_CONSTRAINT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

/**
 * Implements the runtime escape hatches `checkIntRange(value)` and
 * `checkConstraint(value)` generically.
 *
 * A bare 1-arg call is replaced by a chain of validator calls -- one per annotation
 * on the value that carries `@CompileTimeConstraint(V::class)`:
 *
 *   `@IntRange(0,10) val a = checkConstraint(v)`  ->  `IntRangeValidator.validate(v, IntRange(0,10))`
 *
 * No constraint is special-cased here; adding a new `@CompileTimeConstraint` works
 * with no change to this pass. Must run BEFORE the `@ConstrainedBy` extension so the
 * bare call is still the top of the assignment when it is rewritten.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class CheckIntRangeBoundsIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val escapeHatches = buildSet {
            pluginContext.referenceFunctions(CHECK_INT_RANGE_CALLABLE)
                .firstOrNull { it.owner.parameters.size == 1 }?.let { add(it) }
            pluginContext.referenceFunctions(CHECK_CONSTRAINT_CALLABLE).firstOrNull()?.let { add(it) }
        }
        if (escapeHatches.isEmpty()) return
        moduleFragment.transform(ConstraintTransformer(pluginContext, escapeHatches), null)
    }
}

@UnsafeDuringIrConstructionAPI
private class ConstraintTransformer(
    private val pluginContext: IrPluginContext,
    private val escapeHatches: Set<IrSimpleFunctionSymbol>,
) : IrElementTransformerVoidWithContext() {

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer
        if (initializer != null) {
            result.initializer = applyConstraints(initializer, result.annotations)
        }
        return result
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val annotations = (result.symbol.owner as? IrVariable)?.annotations.orEmpty()
        result.value = applyConstraints(result.value, annotations)
        return result
    }

    /**
     * If [expr] is a bare escape-hatch call, replaces it with a chain of
     * `validator.validate(value, annotation)` calls over the `@CompileTimeConstraint`
     * annotations in [annotations]. If there are none, the value is returned bare
     * (its remaining, runtime-only constraints are wrapped on later).
     */
    private fun applyConstraints(expr: IrExpression, annotations: List<IrConstructorCall>): IrExpression {
        if (expr !is IrCall || expr.symbol !in escapeHatches) return expr
        var acc: IrExpression = expr.arguments[0] ?: return expr
        val scope = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scope, expr.startOffset, expr.endOffset)
        for (annotation in annotations) {
            val validator = annotation.constraintValidator() ?: continue
            acc = builder.irCall(validator.validate).apply {
                arguments[0] = builder.irGetObject(validator.objectClass) // dispatch receiver (the validator object)
                arguments[1] = acc                                        // value
                arguments[2] = annotation.deepCopyWithSymbols()           // the annotation instance
            }
        }
        return acc
    }

    /** Resolves `@CompileTimeConstraint(V::class)` on this annotation's class to V's object + validate fn. */
    private fun IrConstructorCall.constraintValidator(): ConstraintValidatorRef? {
        val annotationClass = type.classOrNull?.owner ?: return null
        val meta = annotationClass.getAnnotation(COMPILE_TIME_CONSTRAINT_FQ) ?: return null
        val validatorClass = (meta.arguments[0] as? IrClassReference)?.symbol as? IrClassSymbol ?: return null
        val validate = validatorClass.owner.functions.firstOrNull { it.name.asString() == "validate" } ?: return null
        return ConstraintValidatorRef(validatorClass, validate.symbol)
    }
}

private class ConstraintValidatorRef(
    val objectClass: IrClassSymbol,
    val validate: IrSimpleFunctionSymbol,
)
