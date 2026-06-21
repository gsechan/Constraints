package com.constraints.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irTemporary
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

private val CONSTRAINT_FQ = FqName("com.constraints.Constraint")
private val CHECK_CONSTRAINT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

/**
 * Implements the runtime escape hatch`checkConstraint(value)` generically.
 *
 * A bare 1-arg call is replaced by a block that evaluates the value once and runs each
 * constraint's validator against it -- one per constraint on the value:
 *
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class CheckIntRangeBoundsIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // checkConstraint is the single (generic) escape-hatch function.
        val escapeHatches = pluginContext.referenceFunctions(CHECK_CONSTRAINT_CALLABLE).toSet()
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
     * If [expr] is a bare escape-hatch call, replaces it with a block that evaluates the value
     * once into a temporary, runs each constraint's `validate(value, annotation)` against it
     * (one per `@Constraint` on the value), then yields the temporary. The value is evaluated
     * exactly once and is never transformed -- validators return nothing, so they can only pass
     * or throw. If the value carries no constraints the call's argument is returned bare. Non
     * escape-hatch expressions are left untouched.
     */
    private fun applyConstraints(expr: IrExpression, annotations: List<IrConstructorCall>): IrExpression {
        if (expr !is IrCall || expr.symbol !in escapeHatches) return expr
        val value = expr.arguments[0] ?: return expr
        val applications = annotations.flatMap { it.constraintApplications() }
        if (applications.isEmpty()) return value

        val scope = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scope, expr.startOffset, expr.endOffset)
        return builder.irBlock(resultType = value.type) {
            val tmp = irTemporary(value) // evaluate the value once
            for (application in applications) {
                +irCall(application.validator.validate).apply {
                    arguments[0] = irGetObject(application.validator.objectClass) // dispatch receiver
                    arguments[1] = irGet(tmp)                                     // value
                    arguments[2] = application.annotation.deepCopyWithSymbols()   // annotation instance
                }
            }
            +irGet(tmp) // block evaluates to the (unchanged) value
        }
    }

    /**
     * The (validator, annotation-instance) pairs this annotation contributes. If its class is
     * directly meta-annotated `@Constraint(V::class)` the validator reads *this* annotation
     * instance (e.g. `@IntRange(0, 10)` or `@InverseRange(0, 10)`, where the data lives); for an
     * alias such as `@PositiveInt` it resolves recursively through the `@Constraint`-carrying
     * meta-annotation on its class (e.g. `@IntRange(1, MAX)`).
     */
    private fun IrConstructorCall.constraintApplications(): List<ConstraintApplication> {
        val annotationClass = type.classOrNull?.owner ?: return emptyList()
        val result = mutableListOf<ConstraintApplication>()
        annotationClass.getAnnotation(CONSTRAINT_FQ)?.resolveValidator()?.let {
            result += ConstraintApplication(it, this)
        }
        for (meta in annotationClass.annotations) {
            if (meta.type.classOrNull?.owner?.getAnnotation(CONSTRAINT_FQ) != null) {
                result += meta.constraintApplications()
            }
        }
        return result
    }

    /**
     * Reads the validator class from a `@Constraint(V::class)` construction and locates its
     * `validate` function. A non-object validator (or a missing `validate`) is already a compile
     * error from the FIR `ConstraintValidatorChecker`, so this only returns null as a non-crash
     * guard for the exotic case of a constraint defined in a module the plugin didn't check.
     */
    private fun IrConstructorCall.resolveValidator(): ValidatorRef? {
        val validatorClass = (arguments[0] as? IrClassReference)?.symbol as? IrClassSymbol ?: return null
        if (validatorClass.owner.kind != ClassKind.OBJECT) return null
        val validate = validatorClass.owner.functions.firstOrNull { it.name.asString() == "validate" } ?: return null
        return ValidatorRef(validatorClass, validate.symbol)
    }
}

private class ValidatorRef(
    val objectClass: IrClassSymbol,
    val validate: IrSimpleFunctionSymbol,
)

private class ConstraintApplication(
    val validator: ValidatorRef,
    val annotation: IrConstructorCall,
)
