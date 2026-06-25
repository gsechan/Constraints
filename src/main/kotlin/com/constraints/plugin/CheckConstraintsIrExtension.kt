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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val CONSTRAINT_FQ = FqName("com.constraints.Constraint")
private val ELEMENT_CONSTRAINT_FQ = FqName("com.constraints.ElementConstraint")
private val CHECK_CONSTRAINT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))
private val CHECK_CONSTRAINT_OR_DEFAULT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("checkConstraintOrDefault"))
private val VALIDATE_EACH_ELEMENT_CALLABLE = CallableId(FqName("com.constraints"), Name.identifier("validateEachElement"))
private val CONSTRAINT_EXCEPTION_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ConstraintException"))

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
        val checkConstraintOrDefaultFunction = pluginContext.referenceFunctions(CHECK_CONSTRAINT_OR_DEFAULT_CALLABLE).singleOrNull()
        val validateEachElementFunction = pluginContext.referenceFunctions(VALIDATE_EACH_ELEMENT_CALLABLE).singleOrNull()
        moduleFragment.transform(
            ConstraintTransformer(pluginContext, checkConstraintFunction, checkConstraintOrDefaultFunction, validateEachElementFunction),
            null,
        )
    }
}

@UnsafeDuringIrConstructionAPI
private class ConstraintTransformer(
    private val pluginContext: IrPluginContext,
    private val checkConstraintFunction: IrSimpleFunctionSymbol,
    private val checkConstraintOrDefaultFunction: IrSimpleFunctionSymbol?,
    private val validateEachElementFunction: IrSimpleFunctionSymbol?,
) : IrElementTransformerVoidWithContext() {

    /** Type of `com.constraints.ConstraintException`, the exception checkConstraintOrDefault catches. */
    private val constraintExceptionType: IrType? by lazy {
        pluginContext.referenceClass(CONSTRAINT_EXCEPTION_CLASS_ID)?.typeWith()
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration) as IrVariable
        val initializer = result.initializer
        if (initializer != null) {
            result.initializer = ifCheckConstraintReplaceWithValidation(
                initializer, result.annotations, elementTypeAnnotationsOf(result.type),
            )
        }
        return result
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression) as IrSetValue
        val variable = result.symbol.owner as? IrVariable
        result.value = ifCheckConstraintReplaceWithValidation(
            result.value, variable?.annotations.orEmpty(), elementTypeAnnotationsOf(variable?.type),
        )
        return result
    }

    /**
     * Constraint annotations on a type's first type argument -- the `@IntRange(0, 10)` in
     * `List<@IntRange(0, 10) Int>`. Each is applied per element via `validateEachElement`.
     */
    private fun elementTypeAnnotationsOf(type: IrType?): List<IrConstructorCall> =
        (type as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull?.annotations.orEmpty()

    /**
     * Replaces a `checkConstraint(value)` or `checkConstraintOrDefault(value, default)` call with a
     * block that evaluates the value once into a temporary, runs each constraint's
     * `validate(value, annotation)` against it (one per `@Constraint`/`@ElementConstraint` on the
     * value), then yields the temporary. For the `OrDefault` form the whole thing is wrapped in a
     * try/catch so a [com.constraints.ConstraintException] from any validator yields `default`
     * instead. The value is evaluated exactly once; an unconstrained value is returned bare.
     */
    private fun ifCheckConstraintReplaceWithValidation(
        expr: IrExpression,
        annotations: List<IrConstructorCall>,
        elementTypeAnnotations: List<IrConstructorCall>,
    ): IrExpression {
        if (expr !is IrCall) return expr
        val isPlain = expr.symbol == checkConstraintFunction
        val isOrDefault = checkConstraintOrDefaultFunction != null && expr.symbol == checkConstraintOrDefaultFunction
        if (!isPlain && !isOrDefault) return expr

        val value = expr.arguments[0] ?: return expr
        val constraintApplications = annotations.flatMap { it.getAllConstraints() }
        // Element-level validation comes from both a value-level @ElementConstraint and from
        // constraint annotations on the element type argument (`List<@IntRange(0,10) Int>`); both
        // run per element via validateEachElement.
        val elementApplications = if (validateEachElementFunction != null) {
            annotations.flatMap { it.getElementConstraints() } +
                elementTypeAnnotations.flatMap { it.getAllConstraints() }
        } else {
            emptyList()
        }
        // Unconstrained value: neither hatch can fail, so return the value as-is (default unused).
        if (constraintApplications.isEmpty() && elementApplications.isEmpty()) return value

        val scope = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scope, expr.startOffset, expr.endOffset)
        val validationBlock = builder.irBlock(resultType = value.type) {
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

        if (isPlain) return validationBlock

        // checkConstraintOrDefault(value, default): on a ConstraintException, yield the default.
        val default = expr.arguments[1] ?: return validationBlock
        return wrapInTryCatch(builder, validationBlock, default, value.type) ?: validationBlock
    }

    /**
     * Wraps [tryBody] in `try { … } catch (e: ConstraintException) { default }`, an expression of
     * [type]. Returns null if `ConstraintException` can't be resolved, so the caller falls back to
     * the plain validation block (which still validates, just without the fallback).
     */
    private fun wrapInTryCatch(
        builder: DeclarationIrBuilder,
        tryBody: IrExpression,
        default: IrExpression,
        type: IrType,
    ): IrExpression? {
        val excType = constraintExceptionType ?: return null
        val catchParameter = IrVariableImpl(
            builder.startOffset,
            builder.endOffset,
            IrDeclarationOrigin.CATCH_PARAMETER,
            IrVariableSymbolImpl(),
            Name.identifier("constraintFailure"),
            excType,
            isVar = false,
            isConst = false,
            isLateinit = false,
        ).apply { parent = builder.scope.getLocalDeclarationParent() }

        return IrTryImpl(builder.startOffset, builder.endOffset, type).apply {
            tryResult = tryBody
            catches += IrCatchImpl(builder.startOffset, builder.endOffset, catchParameter, default)
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
