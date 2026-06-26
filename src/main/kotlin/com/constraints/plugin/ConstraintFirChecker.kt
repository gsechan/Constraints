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
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirReturnExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// FIR wiring + dispatch.
//
// This file holds only the compiler-interaction skeleton: the registrar, the position-based
// checkers (property / assignment / return), the constraint-definition checker, and the
// verifyConstraints dispatcher. The per-constraint reading + verification logic lives in the
// sibling *Constraint.kt files (RangeConstraint, DoubleRangeConstraint, SizeConstraint,
// DivisibilityConstraint, CustomConstraint); shared ids/helpers are in
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
        override val functionCallCheckers = setOf(ConstraintArrayWriteChecker, ConstraintLambdaArgumentChecker)
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

/**
 * Return:  every `return <expr>` from a *named* function with a constrained return type must honor it.
 *
 * Lambdas are intentionally excluded. A lambda's resolved return type can carry an element-type
 * constraint by substitution (the `Array<@IntRange Int>` init lambda is `(Int) -> @IntRange Int`), but
 * this context-free checker can't see whether the lambda's result is covered by an enclosing
 * `checkConstraint` (as in `checkConstraint(Array(5) { dynamic })`), so it would wrongly reject a
 * deferred value. Constrained lambda returns are handled with that context by [ConstraintLambdaArgumentChecker]
 * (for `() -> @C T` parameters) and by the `Array(size) { init }` path in verifyElementTypeConstraints.
 */
object ConstraintReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val function = expression.target.labeledElement as? FirFunction ?: return
        if (function is FirAnonymousFunction) return // lambdas are handled at the call/assignment site
        val result = expression.result
        // Runtime-only constraints on the return type; if unsatisfied, stop (as in verifyConstraints).
        if (verifyRuntimeConstraints(function.symbol.returnTypeConstraintKeys(context.session), result, context, reporter)) return
        if (verifyElementConstraints(function.symbol.returnTypeElementConstraintKeys(context.session), result, context, reporter)) return
        if (verifyElementTypeConstraints(function.symbol.resolvedReturnType, result, context, reporter)) return
        function.symbol.returnTypeRange(context.session)?.let { verifyRange(result, it, context, reporter) }
        function.symbol.returnTypeDoubleRange(context.session)?.let { verifyDoubleRange(result, it, context, reporter) }
        function.symbol.returnTypeSize(context.session)?.let { verifySize(result, it, context, reporter) }
        function.symbol.returnTypeStringMatches(context.session).takeIf { it.isNotEmpty() }?.let { verifyStringMatches(result, it, context, reporter) }
        function.symbol.returnTypeDivisibleBy(context.session)?.let { verifyDivisibility(result, it, context, reporter) }
    }
}

/**
 * Indexed array write: `array[i] = value` resolves to `array.set(i, value)`. When `array`'s element
 * type carries constraints (`Array<@IntRange(0, 10) Int>`), the written value must honour them --
 * proven statically where possible (a hard error for a provably-bad value), otherwise deferred to
 * `checkConstraint(value)`. Scoped to `Array<T>` so it stays in lockstep with the IR, which injects
 * the runtime check for arrays only. (An explicit `array.set(i, v)` call is validated too.)
 */
object ConstraintArrayWriteChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.calleeReference.toResolvedNamedFunctionSymbol()?.name?.asString() != "set") return
        val receiverType = (expression.dispatchReceiver ?: expression.explicitReceiver)?.resolvedType ?: return
        if (receiverType.classId != ARRAY_CLASS_ID) return
        if (!receiverType.hasElementConstraints(context.session)) return
        val value = expression.arguments.lastOrNull() ?: return // set(index, value)
        verifyElementWrite(receiverType, value, context, reporter)
    }
}

/**
 * A lambda argument whose parameter's function type has a constrained *return* type -- e.g.
 * `fun make(f: () -> @IntRange(0, 10) Int)` called as `make { 0 }`. The lambda's returned value must
 * honour that constraint, so it is verified statically (a hard error for a provably-bad return). The
 * constraint is read off the *declared* parameter type, so it covers a hand-written `() -> @C T`.
 * (The `Array(size) { init }` constructor's lambda is handled via the element type instead, in
 * verifyElementTypeConstraints, since there the annotation arrives only by type-argument substitution.)
 */
object ConstraintLambdaArgumentChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
        for ((argument, parameter) in mapping) {
            val lambda = argument as? FirAnonymousFunctionExpression ?: continue
            val constraints = parameter.returnTypeRef.coneType.lambdaReturnConstraints(context.session)
            if (constraints.isEmpty()) continue
            val returnValue = lambdaReturnValue(lambda) ?: continue
            verifyValueAgainstConstraints(returnValue, constraints, context, reporter)
        }
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
 *  - `@Size` (CharSequence length / Collection-Map / array size): size inference ([verifySize]).
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
    val hasElementTypeConstraints = symbol.resolvedReturnType.hasElementConstraints(context.session)
    val range = symbol.rangeTarget(context.session)
    val doubleRange = symbol.doubleRangeTarget(context.session)
    val size = symbol.sizeTarget(context.session)
    val stringMatches = symbol.stringMatchTargets(context.session)
    val divisibility = symbol.divisibleBy(context.session)
    if (required.isEmpty() && requiredElement.isEmpty() && !hasElementTypeConstraints &&
        range == null && doubleRange == null && size == null &&
        stringMatches.isEmpty() && divisibility == null) return

    // The escape hatch satisfies every constraint -- the IR backend injects the checks.
    if (isCheckConstraints(rhs)) return

    // Runtime-only constraints (value-level, element-level, and element-type) first.
    if (verifyRuntimeConstraints(required, rhs, context, reporter)) return
    if (verifyElementConstraints(requiredElement, rhs, context, reporter)) return
    if (hasElementTypeConstraints && verifyElementTypeConstraints(symbol.resolvedReturnType, rhs, context, reporter)) return

    if (range != null) verifyRange(rhs, range, context, reporter)
    if (doubleRange != null) verifyDoubleRange(rhs, doubleRange, context, reporter)
    if (size != null) verifySize(rhs, size, context, reporter)
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
