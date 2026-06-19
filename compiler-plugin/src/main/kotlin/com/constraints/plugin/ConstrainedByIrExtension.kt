package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.FqName

private val CONSTRAINED_BY_ANNOTATION = FqName("com.constraints.ConstrainedBy")

/** Backend hook: walks the IR and rewrites `@ConstrainedBy` assignments. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ConstrainedByIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(ConstrainedByIrTransformer(pluginContext), null)
    }
}

/**
 * Wraps every assignment to a `@ConstrainedBy`-annotated local variable in a call
 * to the validator object's `validate` function. The validator class is read
 * from the annotation as a class reference and resolved to its object instance;
 * if it can't be resolved the annotation is skipped (with a stderr warning)
 * rather than crashing the compiler.
 */
@UnsafeDuringIrConstructionAPI
class ConstrainedByIrTransformer(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoidWithContext() {

    // Initialisation:  @ConstrainedBy(V::class) var x = <expr>  ->  ... = V.validate(<expr>)
    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer ?: return result
        val validator = result.resolveValidator() ?: return result
        result.initializer = wrap(initializer, validator)
        return result
    }

    // Reassignment:  x = <expr>  ->  x = V.validate(<expr>)
    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val target = result.symbol.owner as? IrVariable ?: return result
        val validator = target.resolveValidator() ?: return result
        result.value = wrap(result.value, validator)
        return result
    }

    /**
     * Reads the validator class from `@ConstrainedBy(V::class)`, checks it is an
     * `object`, and locates its `validate` function. Returns null (and warns) if
     * the annotation is absent or the validator is unusable, so bad validators
     * degrade gracefully instead of crashing the compiler.
     */
    private fun IrVariable.resolveValidator(): ValidatorRef? {
        val annotation = getAnnotation(CONSTRAINED_BY_ANNOTATION) ?: return null
        val classRef = annotation.arguments[0] as? IrClassReference ?: return null
        val validatorClass = classRef.symbol as? IrClassSymbol ?: return null

        if (validatorClass.owner.kind != ClassKind.OBJECT) {
            System.err.println(
                "constraints plugin: @ConstrainedBy validator '${validatorClass.owner.name}' must be a Kotlin object — check skipped"
            )
            return null
        }

        val validate = validatorClass.owner.functions.firstOrNull { it.name.asString() == "validate" }
        if (validate == null) {
            System.err.println(
                "constraints plugin: @ConstrainedBy validator '${validatorClass.owner.name}' has no validate(value) function — check skipped"
            )
            return null
        }
        return ValidatorRef(validatorClass, validate.symbol)
    }

    private fun wrap(value: IrExpression, validator: ValidatorRef): IrExpression {
        val scopeSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol, value.startOffset, value.endOffset)
        return builder.irCall(validator.validate).apply {
            // arguments[0] = dispatch receiver (the validator singleton); [1] = the value
            arguments[0] = builder.irGetObject(validator.objectClass)
            arguments[1] = value
        }
    }
}

/** A resolved validator: its object class (for the dispatch receiver) + its validate function. */
private class ValidatorRef(
    val objectClass: IrClassSymbol,
    val validate: IrSimpleFunctionSymbol,
)
