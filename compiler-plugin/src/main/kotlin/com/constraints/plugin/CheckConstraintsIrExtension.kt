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
private val ELEMENT_CONSTRAINT_FQ = FqName("com.constraints.ElementConstraint")
private val CHECK_CONSTRAINT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))
private val VALIDATE_EACH_ELEMENT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("validateEachElement"))

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
        val checkConstraintFunction = pluginContext.referenceFunctions(CHECK_CONSTRAINT_CALLABLE).single()
        val validateEachElementFunction = pluginContext.referenceFunctions(VALIDATE_EACH_ELEMENT_CALLABLE).singleOrNull()
        moduleFragment.transform(ConstraintTransformer(pluginContext, checkConstraintFunction, validateEachElementFunction), null)
    }
}

@UnsafeDuringIrConstructionAPI
private class ConstraintTransformer(
    private val pluginContext: IrPluginContext,
    private val checkConstraintFunction: IrSimpleFunctionSymbol,
    private val validateEachElementFunction: IrSimpleFunctionSymbol?,
) : IrElementTransformerVoidWithContext() {

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer
        if (initializer != null) {
            result.initializer = ifCheckConstraintReplaceWithValidation(initializer, result.annotations)
        }
        return result
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val annotations = (result.symbol.owner as? IrVariable)?.annotations.orEmpty()
        result.value = ifCheckConstraintReplaceWithValidation(result.value, annotations)
        return result
    }

    /**
     * If [expr] is a call to checkConstriants(), replaces it with a block that evaluates the value
     * once into a temporary, runs each constraint's `validate(value, annotation)` against it
     * (one per `@Constraint` on the value), then yields the temporary. The value is evaluated
     * exactly once. If the value carries no constraints the call's argument is returned bare.
     */
    private fun ifCheckConstraintReplaceWithValidation(expr: IrExpression, annotations: List<IrConstructorCall>): IrExpression {
        if (expr !is IrCall || expr.symbol != checkConstraintFunction) return expr
        val value = expr.arguments[0] ?: return expr
        val constraintApplications = annotations.flatMap { it.getAllConstraints() }
        val elementApplications = if (validateEachElementFunction != null)
            annotations.flatMap { it.getElementConstraints() } else emptyList()
        if (constraintApplications.isEmpty() && elementApplications.isEmpty()) return value

        val scope = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scope, expr.startOffset, expr.endOffset)
        return builder.irBlock(resultType = value.type) {
            val tmp = irTemporary(value) // evaluate the value once
            // Value-level validators (@Constraint): validate(value, annotation)
            for (application in constraintApplications) {
                +irCall(application.validator.validate).apply {
                    arguments[0] = irGetObject(application.validator.objectClass)
                    arguments[1] = irGet(tmp)
                    arguments[2] = application.annotation.deepCopyWithSymbols()
                }
            }
            // Element-level validators (@ElementConstraint): validateEachElement(collection, annotation, validator)
            for (application in elementApplications) {
                +irCall(validateEachElementFunction!!).apply {
                    arguments[0] = irGet(tmp)                                     // collection
                    arguments[1] = application.annotation.deepCopyWithSymbols()   // annotation instance
                    arguments[2] = irGetObject(application.validator.objectClass)  // validator
                }
            }
            +irGet(tmp)
        }
    }

    /**
     * The (validator, annotation-instance) pairs this annotation contributes. If its class is
     * directly meta-annotated `@Constraint(V::class)` the validator reads *this* annotation
     * instance (e.g. `@IntRange(0, 10)` or `@InverseRange(0, 10)`, where the data lives); for an
     * alias such as `@PositiveInt` it resolves recursively through the `@Constraint`-carrying
     * meta-annotation on its class (e.g. `@IntRange(1, MAX)`).
     */
    private fun IrConstructorCall.getAllConstraints(): List<ConstraintApplication> {
        val annotationClass = type.classOrNull?.owner ?: return emptyList()
        val result = mutableListOf<ConstraintApplication>()
        annotationClass.getAnnotation(CONSTRAINT_FQ)?.getValidatorFromAnnotation()?.let {
            result += ConstraintApplication(it, this)
        }
        for (meta in annotationClass.annotations) {
            if (meta.type.classOrNull?.owner?.getAnnotation(CONSTRAINT_FQ) != null) {
                result += meta.getAllConstraints()
            }
        }
        return result
    }

    /**
     * The (elementValidator, annotation-instance) pairs this annotation contributes via
     * `@ElementConstraint`. The validator is passed to `validateEachElement` along with this
     * annotation instance, so a data-carrying element constraint can read its parameters.
     */
    private fun IrConstructorCall.getElementConstraints(): List<ConstraintApplication> {
        val annotationClass = type.classOrNull?.owner ?: return emptyList()
        val elementConstraintAnnotation = annotationClass.getAnnotation(ELEMENT_CONSTRAINT_FQ) ?: return emptyList()
        val validator = elementConstraintAnnotation.getValidatorFromAnnotation() ?: return emptyList()
        return listOf(ConstraintApplication(validator, this))
    }

    /**
     * Reads the validator class from a `@Constraint(V::class)` or `@ElementConstraint(V::class)`
     * construction and locates its `validate` function. Returns null as a non-crash guard for the
     * exotic case of a constraint defined in a module the plugin didn't check.
     */
    private fun IrConstructorCall.getValidatorFromAnnotation(): ValidatorRef? {
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
