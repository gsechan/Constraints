package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirReturnExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val INT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("IntRange"))
private val CONSTRAINED_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ConstrainedBy"))
private val CHECK_INT_RANGE_ID = CallableId(FqName("com.constraints"), Name.identifier("checkIntRange"))
private val CHECK_CONSTRAINT_ID = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

// ===========================================================================
// Interval lattice -- the part that does the actual reasoning. Pure Kotlin, so
// it is fully testable on its own and independent of the (more volatile) FIR API.
// ===========================================================================

internal data class Interval(val min: Long, val max: Long) {
    val isUnknown: Boolean get() = this == UNKNOWN

    fun subsetOf(other: Interval): Boolean = min >= other.min && max <= other.max

    /** True if the two ranges share at least one value (so the assignment *could* be valid). */
    fun overlaps(other: Interval): Boolean = min <= other.max && other.min <= max

    operator fun plus(o: Interval) = if (isUnknown || o.isUnknown) UNKNOWN else of(min + o.min, max + o.max)
    operator fun minus(o: Interval) = if (isUnknown || o.isUnknown) UNKNOWN else of(min - o.max, max - o.min)
    operator fun times(o: Interval): Interval {
        if (isUnknown || o.isUnknown) return UNKNOWN
        val corners = longArrayOf(min * o.min, min * o.max, max * o.min, max * o.max)
        return of(corners.min(), corners.max())
    }

    operator fun div(o: Interval): Interval {
        if (isUnknown || o.isUnknown) return UNKNOWN
        // If the divisor range includes 0 we can't bound the result (and it might divide by zero).
        if (o.min <= 0 && o.max >= 0) return UNKNOWN
        // Integer (truncating) division is monotonic at the corners once 0 is excluded from the divisor.
        val corners = longArrayOf(min / o.min, min / o.max, max / o.min, max / o.max)
        return of(corners.min(), corners.max())
    }

    operator fun rem(o: Interval): Interval {
        if (isUnknown || o.isUnknown) return UNKNOWN
        // Divisor may be 0 -> can't bound (reported as a divide/modulo-by-zero error elsewhere).
        if (o.min <= 0 && o.max >= 0) return UNKNOWN
        // `%` keeps the sign of the dividend, with |result| <= |divisor| - 1, and is also
        // bounded by the dividend itself (when |dividend| < |divisor| the result is the dividend).
        val maxRemainder = maxOf(kotlin.math.abs(o.min), kotlin.math.abs(o.max)) - 1
        val lo = if (min < 0) maxOf(min, -maxRemainder) else 0L
        val hi = if (max > 0) minOf(max, maxRemainder) else 0L
        return of(lo, hi)
    }

    companion object {
        /** "Could be anything" -- the top of the lattice; never a subset of a real range. */
        val UNKNOWN = Interval(Long.MIN_VALUE, Long.MAX_VALUE)

        fun point(v: Long) = Interval(v, v)

        /**
         * Overflow-aware constructor. Interval math is done in [Long]; if the result
         * leaves the 32-bit Int range the real Int arithmetic could wrap, so we must
         * widen to [UNKNOWN] rather than trust a value that can't actually occur.
         */
        fun of(lo: Long, hi: Long): Interval =
            if (lo < Int.MIN_VALUE.toLong() || hi > Int.MAX_VALUE.toLong()) UNKNOWN else Interval(lo, hi)
    }
}

// ===========================================================================
// FIR wiring
// ===========================================================================

class IntRangeFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::IntRangeCheckersExtension
        // Public registration path for the plugin's diagnostics (replaces the
        // internal renderer-map / RootDiagnosticRendererFactory approach).
        registerDiagnosticContainers(ConstraintErrors)
    }
}

class IntRangeCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers = object : DeclarationCheckers() {
        override val propertyCheckers = setOf(IntRangePropertyChecker)
    }

    override val expressionCheckers = object : ExpressionCheckers() {
        override val variableAssignmentCheckers = setOf(IntRangeAssignmentChecker)
        override val returnExpressionCheckers = setOf(IntRangeReturnChecker)
    }
}

/** Initialisation:  `@IntRange(min,max) var x = <initializer>` */
object IntRangePropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val initializer = declaration.initializer ?: return
        verifyConstraints(declaration.symbol, initializer, context, reporter)
    }
}

/** Reassignment, including `a++` / `a--` (which desugar to `a = a.inc()` / `a.dec()`). */
object IntRangeAssignmentChecker : FirVariableAssignmentChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        val symbol = expression.lValue.resolvedVariableSymbolOrNull() ?: return
        verifyConstraints(symbol, expression.rValue, context, reporter)
    }
}

/** Return:  every `return <expr>` from a function with an @IntRange return type must honor it. */
object IntRangeReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val function = expression.target.labeledElement as? FirFunction ?: return
        val target = function.symbol.returnTypeIntRange(context.session) ?: return
        verify(expression.result, target, context, reporter)
    }
}

/**
 * Verifies every constraint declared on [symbol] against its assigned [rhs].
 *
 * A bare `checkConstraint(value)` defers all of them to runtime and is always accepted.
 * Otherwise each constraint must be proven statically:
 *  - `@ConstrainedBy(V)` (runtime-only): provable only if [rhs] is *already known* to satisfy
 *    every required validator -- i.e. it reads a variable whose declared `@ConstrainedBy`
 *    validators are a superset. This is sound because every write to that variable is itself
 *    checked, and validators are required to be pure (a stateless predicate on the value), so a
 *    value that satisfied V when written still satisfies V now. A literal or arithmetic result
 *    can't be proven against an opaque validator, so it needs `checkConstraint`.
 *  - `@IntRange` (compile-time): proven by interval inference ([verify]).
 */
private fun verifyConstraints(
    symbol: FirVariableSymbol<*>,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    val required = symbol.constrainedByValidators(context.session)
    val target = symbol.intRangeBounds(context.session)
    if (required.isEmpty() && target == null) return

    // The escape hatch satisfies every constraint -- the IR backend injects the checks.
    if (isBareEscapeHatch(rhs)) return

    if (required.isNotEmpty()) {
        val missing = required - knownValidators(rhs, context.session)
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { "@ConstrainedBy(${it.shortClassName.asString()})" }
            val plural = if (missing.size == 1) "it" else "they"
            reporter.reportOn(
                rhs.source,
                ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                "Cannot prove this satisfies $names: $plural cannot be checked statically against an opaque " +
                    "validator. Wrap it in checkConstraint(value) to validate at runtime, or assign from a " +
                    "value already declared with the same @ConstrainedBy.",
                context,
            )
            return // don't also pile on an @IntRange error for the same assignment
        }
    }

    if (target != null) verify(rhs, target, context, reporter)
}

/**
 * The set of `@ConstrainedBy` validators [expr] is *already known* to satisfy: the declared
 * validators of the variable it reads (a sound invariant, since every write to that variable
 * is checked). Any other expression is known to satisfy nothing.
 */
private fun knownValidators(expr: FirExpression?, session: FirSession): Set<ClassId> = when (expr) {
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.constrainedByValidators(session) ?: emptySet()

    is FirDesugaredAssignmentValueReferenceExpression ->
        knownValidators(expr.expressionRef.value, session)

    else -> emptySet()
}

/** Reports an error unless [rhs]'s inferred interval is provably within [target]. */
private fun verify(rhs: FirExpression, target: Interval, context: CheckerContext, reporter: DiagnosticReporter) {
    // A possible divide-by-zero anywhere in the expression is a hard error in its
    // own right -- report it and stop (the range check would just add noise).
    if (reportDivisionByZero(rhs, context, reporter)) return
    // Single-arg `checkIntRange(value)`: explicit escape hatch; the IR backend fills
    // its bounds from `target`, so accept it here without inferring a range.
    if (isBareEscapeHatch(rhs)) return
    val inferred = inferInterval(rhs, context.session)
    if (inferred.subsetOf(target)) return // statically proven in range -> no runtime check needed

    val message = if (inferred.overlaps(target)) {
        // Partial overlap (or an unknown range): some values would be valid, so a
        // runtime check is the right escape hatch.
        val range = if (inferred.isUnknown) {
            "its range cannot be determined statically"
        } else {
            "its range [${inferred.min}, ${inferred.max}] is not fully within it"
        }
        "Cannot prove this satisfies @IntRange(${target.min}, ${target.max}): $range. " +
            "Wrap it in checkIntRange(value) to check at runtime."
    } else {
        // Disjoint ranges: the value can never be valid, so a runtime check would
        // always fail -- report it as a definite mismatch instead.
        "Value range [${inferred.min}, ${inferred.max}] does not match " +
            "@IntRange(${target.min}, ${target.max}): the ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}

/**
 * Walks [expr] for any integer division whose divisor's range is known to include 0,
 * reporting a divide-by-zero error for each such division. Returns true if any fired.
 */
private fun reportDivisionByZero(expr: FirExpression?, context: CheckerContext, reporter: DiagnosticReporter): Boolean {
    when (expr) {
        is FirDesugaredAssignmentValueReferenceExpression ->
            return reportDivisionByZero(expr.expressionRef.value, context, reporter)

        is FirFunctionCall -> {
            var found = reportDivisionByZero(expr.dispatchReceiver ?: expr.explicitReceiver, context, reporter)
            for (arg in expr.arguments) {
                if (reportDivisionByZero(arg, context, reporter)) found = true
            }
            val opName = expr.calleeReference.toResolvedNamedFunctionSymbol()?.name?.asString()
            if (opName == "div" || opName == "rem") {
                val divisor = inferInterval(expr.arguments.firstOrNull(), context.session)
                if (!divisor.isUnknown && divisor.min <= 0 && divisor.max >= 0) {
                    val op = if (opName == "rem") "modulo" else "divide"
                    reporter.reportOn(
                        expr.source,
                        ConstraintErrors.INTRANGE_DIVISION_BY_ZERO,
                        "Possible $op by zero: the divisor's range [${divisor.min}, ${divisor.max}] includes 0.",
                        context,
                    )
                    found = true
                }
            }
            return found
        }

        else -> return false
    }
}

/** True if [expr] is a bare 1-arg `checkIntRange(value)` or `checkConstraint(value)` escape hatch. */
private fun isBareEscapeHatch(expr: FirExpression?): Boolean =
    expr is FirFunctionCall &&
        expr.calleeReference.toResolvedNamedFunctionSymbol()?.callableId in setOf(CHECK_INT_RANGE_ID, CHECK_CONSTRAINT_ID) &&
        expr.arguments.size == 1

// ===========================================================================
// Interval inference over the resolved FIR tree
// ===========================================================================

private fun inferInterval(expr: FirExpression?, session: FirSession): Interval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? Number)?.let { Interval.point(it.toLong()) } ?: Interval.UNKNOWN

    is FirFunctionCall -> inferCall(expr, session)

    // A bare variable read: use its declared @IntRange (a sound invariant, since every
    // write to it is itself checked). Unannotated variables are unknown.
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.intRangeBounds(session) ?: Interval.UNKNOWN

    // `a++` / `a += ...` desugar so the variable read becomes this reference wrapper.
    is FirDesugaredAssignmentValueReferenceExpression ->
        inferInterval(expr.expressionRef.value, session)

    else -> Interval.UNKNOWN
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

private fun inferCall(call: FirFunctionCall, session: FirSession): Interval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN

    // Escape hatch: checkIntRange(value, lo, hi) guarantees a result within [lo, hi].
    if (callee.callableId == CHECK_INT_RANGE_ID) {
        val lo = ((call.arguments.getOrNull(1) as? FirLiteralExpression)?.value as? Number)?.toInt()
        val hi = ((call.arguments.getOrNull(2) as? FirLiteralExpression)?.value as? Number)?.toInt()
        return if (lo != null && hi != null) Interval(lo.toLong(), hi.toLong()) else Interval.UNKNOWN
    }

    // Integer arithmetic: receiver <op> arg, plus inc()/dec() from ++/--.
    // NOTE: matched by name; assumes Int operands (the constrained type is Int).
    val receiver = inferInterval(call.dispatchReceiver ?: call.explicitReceiver, session)
    return when (callee.name.asString()) {
        "inc" -> receiver + Interval.point(1)
        "dec" -> receiver - Interval.point(1)
        "unaryMinus" -> Interval.point(0) - receiver
        "plus" -> receiver + inferInterval(call.arguments.firstOrNull(), session)
        "minus" -> receiver - inferInterval(call.arguments.firstOrNull(), session)
        "times" -> receiver * inferInterval(call.arguments.firstOrNull(), session)
        "div" -> receiver / inferInterval(call.arguments.firstOrNull(), session)
        "rem" -> receiver % inferInterval(call.arguments.firstOrNull(), session)
        // Any other call: trust an @IntRange on its return type, if it has one.
        else -> callee.returnTypeIntRange(session) ?: Interval.UNKNOWN
    }
}

/**
 * The set of `@ConstrainedBy` validator classes this variable is declared to satisfy.
 * A validator *class* (not the annotation) is the constraint's identity, so a direct
 * `@ConstrainedBy(V)` and an alias that resolves to `V` are interchangeable.
 */
private fun FirVariableSymbol<*>.constrainedByValidators(session: FirSession): Set<ClassId> =
    resolvedAnnotationsWithArguments.mapNotNull { it.constrainedByValidatorClassId(session) }.toSet()

/**
 * The validator class behind a `@ConstrainedBy` constraint, or null if this annotation is not
 * one. Read off `@ConstrainedBy(V::class)` directly, or -- for an alias annotation class that is
 * itself meta-annotated `@ConstrainedBy(V::class)` -- off that meta-annotation (mirrors [rangeBounds]).
 */
private fun FirAnnotation.constrainedByValidatorClassId(session: FirSession): ClassId? {
    val classId = toAnnotationClassId(session) ?: return null
    if (classId == CONSTRAINED_BY_CLASS_ID) return validatorArgumentClassId()
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    val meta = classSymbol.resolvedAnnotationsWithArguments.firstOrNull {
        it.toAnnotationClassId(session) == CONSTRAINED_BY_CLASS_ID
    } ?: return null
    return meta.validatorArgumentClassId()
}

/** Reads the `validator = V::class` argument of a `@ConstrainedBy` annotation as the class id of `V`. */
private fun FirAnnotation.validatorArgumentClassId(): ClassId? {
    val arg = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == "validator" }?.value
    val getClass = arg as? FirGetClassCall ?: return null
    return (getClass.argument as? FirResolvedQualifier)?.classId
}

/** The `@IntRange` bounds applied to this variable (directly or via an alias), or null. */
private fun FirVariableSymbol<*>.intRangeBounds(session: FirSession): Interval? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.rangeBounds(session) }

/** The `@IntRange` bounds on this callable's return type (directly or via an alias), or null. */
private fun FirCallableSymbol<*>.returnTypeIntRange(session: FirSession): Interval? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.rangeBounds(session) }

/**
 * The `[min, max]` this annotation constrains a value to: read off `@IntRange` directly,
 * or -- for an alias such as `@PositiveInt` -- off an `@IntRange` meta-annotation placed
 * on the annotation's own declaration.
 */
private fun FirAnnotation.rangeBounds(session: FirSession): Interval? {
    if (toAnnotationClassId(session) == INT_RANGE_CLASS_ID) return readRange(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    val metaIntRange = classSymbol.resolvedAnnotationsWithArguments.firstOrNull {
        it.toAnnotationClassId(session) == INT_RANGE_CLASS_ID
    } ?: return null
    return readRange(metaIntRange)
}

private fun readRange(intRange: FirAnnotation): Interval? {
    val min = intRange.intArgument("min") ?: return null
    val max = intRange.intArgument("max") ?: return null
    return Interval(min.toLong(), max.toLong())
}

private fun FirAnnotation.intArgument(name: String): Int? {
    val expr = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == name }?.value
    // FIR stores integer-literal values as Long, so go through Number, not `as? Int`.
    return ((expr as? FirLiteralExpression)?.value as? Number)?.toInt()
}
