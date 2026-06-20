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
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val COMPILE_TIME_CONSTRAINT_FQ = FqName("com.constraints.CompileTimeConstraint")
private val CONSTRAINED_BY_FQ = FqName("com.constraints.ConstrainedBy")
private val CHECK_INT_RANGE_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkIntRange"))
private val CHECK_CONSTRAINT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

/**
 * Implements the runtime escape hatches `checkIntRange(value)` and
 * `checkConstraint(value)` generically.
 *
 * A bare 1-arg call is replaced by a chain of validator calls -- one per constraint on
 * the value:
 *
 *  - `@CompileTimeConstraint(V::class)` constraints (e.g. `@IntRange`):
 *      `@IntRange(0,10) val a = checkConstraint(v)`  ->  `IntRangeValidator.validate(v, IntRange(0,10))`
 *  - `@ConstrainedBy(V::class)` runtime-only constraints:
 *      `@ConstrainedBy(Even::class) val a = checkConstraint(v)`  ->  `Even.validate(v)`
 *
 * For `@ConstrainedBy` this escape hatch is the *only* way to assign: the FIR checker
 * rejects any other RHS, since a runtime validator can never be proven statically. No
 * constraint is otherwise special-cased; adding a new `@CompileTimeConstraint` works with
 * no change to this pass.
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
     * If [expr] is a bare escape-hatch call, replaces it with a chain of validator calls
     * over [annotations]: `validate(value, annotation)` for each `@CompileTimeConstraint`
     * and `validate(value)` for each `@ConstrainedBy`. If the value carries no constraints
     * the call's argument is returned bare. Non escape-hatch expressions are left untouched.
     */
    private fun applyConstraints(expr: IrExpression, annotations: List<IrConstructorCall>): IrExpression {
        if (expr !is IrCall || expr.symbol !in escapeHatches) return expr
        var acc: IrExpression = expr.arguments[0] ?: return expr
        val scope = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scope, expr.startOffset, expr.endOffset)
        for (annotation in annotations) {
            // @CompileTimeConstraint validators: validate(value, annotationInstance).
            for (application in annotation.constraintApplications()) {
                acc = builder.irCall(application.validator.validate).apply {
                    arguments[0] = builder.irGetObject(application.validator.objectClass) // dispatch receiver
                    arguments[1] = acc                                                    // value
                    arguments[2] = application.annotation.deepCopyWithSymbols()           // annotation instance
                }
            }
            // @ConstrainedBy runtime validators: validate(value).
            for (validator in annotation.constrainedByValidators()) {
                acc = builder.irCall(validator.validate).apply {
                    arguments[0] = builder.irGetObject(validator.objectClass) // dispatch receiver
                    arguments[1] = acc                                        // value
                }
            }
        }
        return acc
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
     * The `@ConstrainedBy` validators implied by this annotation: one if it *is*
     * `@ConstrainedBy(V::class)`, plus one if its annotation class is itself meta-annotated
     * `@ConstrainedBy(V::class)` (the alias case).
     */
    private fun IrConstructorCall.constrainedByValidators(): List<ConstrainedByValidatorRef> {
        val result = mutableListOf<ConstrainedByValidatorRef>()
        if (type.classFqName == CONSTRAINED_BY_FQ) {
            resolveConstrainedByValidator()?.let { result += it }
        }
        type.classOrNull?.owner?.getAnnotation(CONSTRAINED_BY_FQ)?.resolveConstrainedByValidator()?.let { result += it }
        return result
    }

    /**
     * Reads the validator class from a `@ConstrainedBy(V::class)` construction, checks it is an
     * `object`, and locates its single-arg `validate` function. Returns null (and warns) if the
     * validator is unusable, so bad validators degrade gracefully instead of crashing the compiler.
     */
    private fun IrConstructorCall.resolveConstrainedByValidator(): ConstrainedByValidatorRef? {
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
                "constraints plugin: @ConstrainedBy validator '${validatorClass.owner.name}' has no validate(value) function — check skipped"
            )
            return null
        }
        return ConstrainedByValidatorRef(validatorClass, validate.symbol)
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

/** A resolved `@ConstrainedBy` validator: its object class (dispatch receiver) + its single-arg validate fn. */
private class ConstrainedByValidatorRef(
    val objectClass: IrClassSymbol,
    val validate: IrSimpleFunctionSymbol,
)
