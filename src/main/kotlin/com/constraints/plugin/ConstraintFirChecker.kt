package com.constraints.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirReturnExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// FIR wiring + dispatch.
//
// This file holds only the compiler-interaction skeleton: the registrar, the position-based
// checkers (property / assignment / return), the constraint-definition checker, and the
// verifyConstraints dispatcher. The per-constraint reading + verification logic lives in the
// sibling *Constraint.kt files (RangeConstraint, DoubleRangeConstraint, StringLengthConstraint,
// CollectionSizeConstraint, DivisibilityConstraint, CustomConstraint); shared ids/helpers are in
// ConstraintCommon.kt.
// ===========================================================================

class ConstraintFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::ConstraintCheckersExtension
        // Public registration path for the plugin's diagnostics (replaces the
        // internal renderer-map / RootDiagnosticRendererFactory approach).
        registerDiagnosticContainers(ConstraintErrors)
    }
}

class ConstraintCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers = object : DeclarationCheckers() {
        override val propertyCheckers = setOf(ConstraintPropertyChecker)
        override val regularClassCheckers = setOf(ConstraintValidatorChecker)
    }

    override val expressionCheckers = object : ExpressionCheckers() {
        override val variableAssignmentCheckers = setOf(ConstraintAssignmentChecker)
        override val returnExpressionCheckers = setOf(ConstraintReturnChecker)
        override val annotationCheckers = setOf(DivisibleByDivisorChecker)
    }
}

/** Initialisation:  `@IntRange(min,max) var x = <initializer>` */
object ConstraintPropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val initializer = declaration.initializer ?: return
        verifyConstraints(declaration.symbol, initializer, context, reporter)
    }
}

/** Reassignment, including `a++` / `a--` (which desugar to `a = a.inc()` / `a.dec()`). */
object ConstraintAssignmentChecker : FirVariableAssignmentChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        val symbol = expression.lValue.resolvedVariableSymbolOrNull() ?: return
        verifyConstraints(symbol, expression.rValue, context, reporter)
    }
}

/** Return:  every `return <expr>` from a function with a constrained return type must honor it. */
object ConstraintReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val function = expression.target.labeledElement as? FirFunction ?: return
        val result = expression.result
        // Runtime-only constraints on the return type; if unsatisfied, stop (as in verifyConstraints).
        if (verifyRuntimeConstraints(function.symbol.returnTypeConstraintKeys(context.session), result, context, reporter)) return
        if (verifyElementConstraints(function.symbol.returnTypeElementConstraintKeys(context.session), result, context, reporter)) return
        if (verifyElementTypeConstraints(function.symbol.resolvedReturnType.elementTypeAnnotations(context.session), result, context, reporter)) return
        function.symbol.returnTypeRange(context.session)?.let { verifyRange(result, it, context, reporter) }
        function.symbol.returnTypeDoubleRange(context.session)?.let { verifyDoubleRange(result, it, context, reporter) }
        function.symbol.returnTypeStringLength(context.session)?.let { verifyStringLength(result, it, context, reporter) }
        function.symbol.returnTypeCollectionSize(context.session)?.let { verifyCollectionSize(result, it, context, reporter) }
        function.symbol.returnTypeStringMatches(context.session).takeIf { it.isNotEmpty() }?.let { verifyStringMatches(result, it, context, reporter) }
        function.symbol.returnTypeDivisibleBy(context.session)?.let { verifyDivisibility(result, it, context, reporter) }
    }
}

/**
 * Validity of a constraint *definition*: an annotation class meta-annotated `@Constraint(V::class)`
 * must name a validator that is a Kotlin `object` (the plugin runs its singleton instance). A
 * non-object validator is a compile error reported here -- at the definition -- rather than a
 * constraint that silently does nothing at runtime. (`validate` is guaranteed by the
 * `KClass<out Validator<*, *>>` bound, so only object-ness needs checking.)
 */
object ConstraintValidatorChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val constraint = declaration.symbol.resolvedAnnotationsWithArguments.firstOrNull {
            it.toAnnotationClassId(context.session) == CONSTRAINT_CLASS_ID
        } ?: return
        val validatorId = constraint.validatorClassId() ?: return
        val validator = context.session.symbolProvider
            .getClassLikeSymbolByClassId(validatorId) as? FirRegularClassSymbol ?: return
        if (validator.classKind != ClassKind.OBJECT) {
            reporter.reportOn(
                constraint.source,
                ConstraintErrors.CONSTRAINT_VALIDATOR_INVALID,
                "@Constraint validator '${validatorId.shortClassName.asString()}' must be a Kotlin object (a singleton).",
                context,
            )
        }
    }
}

/** Reads the `validator = V::class` argument of a `@Constraint` annotation as the class id of `V`. */
private fun FirAnnotation.validatorClassId(): ClassId? {
    val arg = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == "validator" }?.value
    val getClass = arg as? FirGetClassCall ?: return null
    return (getClass.argument as? FirResolvedQualifier)?.classId
}

/**
 * Verifies every constraint declared on [symbol] against its assigned [rhs].
 *
 * A bare `checkConstraint(value)` defers all of them to runtime and is always accepted.
 * Otherwise each constraint must be proven statically:
 *  - runtime-only `@Constraint`s: provable only by transfer from a value already known to satisfy
 *    the identical constraint ([verifyRuntimeConstraints], in CustomConstraint.kt).
 *  - `@ElementConstraint`: likewise, by transfer ([verifyElementConstraints]).
 *  - `@IntRange` / `@LongRange` / `@ShortRange` / `@ByteRange`: interval inference ([verifyRange]).
 *  - `@FloatRange` / `@DoubleRange`: double-interval inference ([verifyDoubleRange]).
 *  - `@StringLength` / `@CollectionSize`: length inference ([verifyStringLength] / [verifyCollectionSize]).
 *  - `@DivisibleBy` / `@LongDivisibleBy` etc.: residue inference ([verifyDivisibility]).
 */
private fun verifyConstraints(
    symbol: FirVariableSymbol<*>,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    val required = symbol.runtimeConstraintKeys(context.session)
    val requiredElement = symbol.elementConstraintKeys(context.session)
    val elementTypeAnnotations = symbol.resolvedReturnType.elementTypeAnnotations(context.session)
    val range = symbol.rangeTarget(context.session)
    val doubleRange = symbol.doubleRangeTarget(context.session)
    val stringLength = symbol.stringLengthTarget(context.session)
    val collectionSize = symbol.collectionSizeTarget(context.session)
    val stringMatches = symbol.stringMatchTargets(context.session)
    val divisibility = symbol.divisibleBy(context.session)
    if (required.isEmpty() && requiredElement.isEmpty() && requiredElementType.isEmpty() &&
        elementTypeAnnotations.isEmpty() && range == null && doubleRange == null &&
        stringLength == null && collectionSize == null && divisibility == null) return

    // The escape hatch satisfies every constraint -- the IR backend injects the checks.
    if (isCheckConstraints(rhs)) return

    // Runtime-only constraints (value-level, element-level, and element-type) first.
    if (verifyRuntimeConstraints(required, rhs, context, reporter)) return
    if (verifyElementConstraints(requiredElement, rhs, context, reporter)) return
    if (verifyElementTypeConstraints(elementTypeAnnotations, rhs, context, reporter)) return

    if (range != null) verifyRange(rhs, range, context, reporter)
    if (doubleRange != null) verifyDoubleRange(rhs, doubleRange, context, reporter)
    if (stringLength != null) verifyStringLength(rhs, stringLength, context, reporter)
    if (collectionSize != null) verifyCollectionSize(rhs, collectionSize, context, reporter)
    if (stringMatches.isNotEmpty()) verifyStringMatches(rhs, stringMatches, context, reporter)
    if (divisibility != null) verifyDivisibility(rhs, divisibility, context, reporter)
}

/**
 * Resolves the variable an lValue refers to, unwrapping the desugared reference
 * wrapper that `a++` / `a += ...` use in place of a direct property access.
 */
private fun FirExpression.resolvedVariableSymbolOrNull(): FirVariableSymbol<*>? {
    val access = when (this) {
        is FirQualifiedAccessExpression -> this
        is FirDesugaredAssignmentValueReferenceExpression -> expressionRef.value as? FirQualifiedAccessExpression
        else -> null
    }
    return access?.calleeReference?.toResolvedVariableSymbol()
}
