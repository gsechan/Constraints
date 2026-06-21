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

private val COMPILE_TIME_CONSTRAINT_FQ = FqName("com.constraints.CompileTimeConstraint")
private val CONSTRAINED_BY_FQ = FqName("com.constraints.ConstrainedBy")
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
     * (one per `@CompileTimeConstraint` and per `@ConstrainedBy` constraint), then yields the
     * temporary. The value is evaluated exactly once and is never transformed -- validators
     * return nothing, so they can only pass or throw. If the value carries no constraints the
     * call's argument is returned bare. Non escape-hatch expressions are left untouched.
     */
    private fun applyConstraints(expr: IrExpression, annotations: List<IrConstructorCall>): IrExpression {
        if (expr !is IrCall || expr.symbol !in escapeHatches) return expr
        val value = expr.arguments[0] ?: return expr
        val applications = annotations.flatMap { it.constraintApplications() + it.constrainedByApplications() }
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
     * The (validator, annotation-instance) pairs implied by this annotation: one if its
     * class carries `@CompileTimeConstraint` directly, plus any from constraint
     * *meta*-annotations on its class -- so an alias like `@PositiveInt` resolves through
     * its `@IntRange(1, MAX)` meta-annotation.
     */
    private fun IrConstructorCall.constraintApplications(): List<ConstraintApplication> {
        val annotationClass = type.classOrNull?.owner ?: return emptyList()
        val result = mutableListOf<ConstraintApplication>()
        annotationClass.getAnnotation(COMPILE_TIME_CONSTRAINT_FQ)?.validatorRef()?.let {
            result += ConstraintApplication(it, this)
        }
        for (meta in annotationClass.annotations) {
            if (meta.type.classOrNull?.owner?.getAnnotation(COMPILE_TIME_CONSTRAINT_FQ) != null) {
                result += meta.constraintApplications()
            }
        }
        return result
    }

    /** Resolves the `KClass<out ConstraintValidator>` of a `@CompileTimeConstraint` to its object + validate fn. */
    private fun IrConstructorCall.validatorRef(): ConstraintValidatorRef? {
        val validatorClass = (arguments[0] as? IrClassReference)?.symbol as? IrClassSymbol ?: return null
        val validate = validatorClass.owner.functions.firstOrNull { it.name.asString() == "validate" } ?: return null
        return ConstraintValidatorRef(validatorClass, validate.symbol)
    }

    /**
     * The `@ConstrainedBy` application implied by this annotation, if its annotation class is
     * meta-annotated `@ConstrainedBy(V::class)`. The validator reads *this* immediate annotation
     * (e.g. `@InverseRange(0, 10)`), where the data lives. `@ConstrainedBy` is a meta-annotation
     * only, so there is no direct-on-the-value case.
     */
    private fun IrConstructorCall.constrainedByApplications(): List<ConstraintApplication> {
        val validator = type.classOrNull?.owner?.getAnnotation(CONSTRAINED_BY_FQ)?.resolveConstrainedByValidator()
            ?: return emptyList()
        return listOf(ConstraintApplication(validator, this))
    }

    /**
     * Reads the validator class from a `@ConstrainedBy(V::class)` construction, checks it is an
     * `object`, and locates its `validate` function. Returns null (and warns) if the validator is
     * unusable, so bad validators degrade gracefully instead of crashing the compiler.
     */
    private fun IrConstructorCall.resolveConstrainedByValidator(): ConstraintValidatorRef? {
        val validatorClass = (arguments[0] as? IrClassReference)?.symbol as? IrClassSymbol ?: return null
        if (validatorClass.owner.kind != ClassKind.OBJECT) {
            System.err.println(
                "constraints plugin: @ConstrainedBy validator '${validatorClass.owner.name}' must be a Kotlin object — check skipped"
            )
            return null
        }
        val validate = validatorClass.owner.functions.firstOrNull { it.name.asString() == "validate" }
        if (validate == null) {
            System.err.println(
                "constraints plugin: @ConstrainedBy validator '${validatorClass.owner.name}' has no validate(...) function — check skipped"
            )
            return null
        }
        return ConstraintValidatorRef(validatorClass, validate.symbol)
    }
}

private class ConstraintValidatorRef(
    val objectClass: IrClassSymbol,
    val validate: IrSimpleFunctionSymbol,
)

private class ConstraintApplication(
    val validator: ConstraintValidatorRef,
    val annotation: IrConstructorCall,
)
