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
import org.jetbrains.kotlin.fir.declarations.resolvedAnnotationsWithArguments
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirReturnExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.resolvedReturnType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.toAnnotationClassId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val INT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("IntRange"))
private val CHECK_INT_RANGE_ID = CallableId(FqName("com.constraints"), Name.identifier("checkIntRange"))

// ===========================================================================
// Interval lattice -- the part that does the actual reasoning. Pure Kotlin, so
// it is fully testable on its own and independent of the (more volatile) FIR API.
// ===========================================================================

internal data class Interval(val min: Long, val max: Long) {
    val isUnknown: Boolean get() = this == UNKNOWN

    fun subsetOf(other: Interval): Boolean = min >= other.min && max <= other.max

    operator fun plus(o: Interval) = if (isUnknown || o.isUnknown) UNKNOWN else of(min + o.min, max + o.max)
    operator fun minus(o: Interval) = if (isUnknown || o.isUnknown) UNKNOWN else of(min - o.max, max - o.min)
    operator fun times(o: Interval): Interval {
        if (isUnknown || o.isUnknown) return UNKNOWN
        val corners = longArrayOf(min * o.min, min * o.max, max * o.min, max * o.max)
        return of(corners.min(), corners.max())
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
    }
}

class IntRangeCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    init {
        // Touch the renderer object so its `init` registers the diagnostic message.
        ConstraintErrorMessages
    }

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
    override fun check(declaration: FirProperty, context: CheckerContext, reporter: DiagnosticReporter) {
        val target = declaration.symbol.intRangeBounds(context.session) ?: return
        val initializer = declaration.initializer ?: return
        verify(initializer, target, context, reporter)
    }
}

/** Reassignment, including `a++` / `a--` (which desugar to `a = a.inc()` / `a.dec()`). */
object IntRangeAssignmentChecker : FirVariableAssignmentChecker(MppCheckerKind.Common) {
    override fun check(expression: FirVariableAssignment, context: CheckerContext, reporter: DiagnosticReporter) {
        val target = (expression.lValue as? FirQualifiedAccessExpression)
            ?.calleeReference?.toResolvedVariableSymbol()
            ?.intRangeBounds(context.session) ?: return
        verify(expression.rValue, target, context, reporter)
    }
}

/** Return:  every `return <expr>` from a function with an @IntRange return type must honour it. */
object IntRangeReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    override fun check(expression: FirReturnExpression, context: CheckerContext, reporter: DiagnosticReporter) {
        val function = expression.target.labeledElement as? FirFunction ?: return
        val target = function.symbol.returnTypeIntRange(context.session) ?: return
        verify(expression.result, target, context, reporter)
    }
}

/** Reports an error unless [rhs]'s inferred interval is provably within [target]. */
private fun verify(rhs: FirExpression, target: Interval, context: CheckerContext, reporter: DiagnosticReporter) {
    val inferred = inferInterval(rhs, context.session)
    if (inferred.subsetOf(target)) return // statically proven in range -> no runtime check needed

    val detail = if (inferred.isUnknown) {
        "its range cannot be determined statically"
    } else {
        "its range [${inferred.min}, ${inferred.max}] is not contained in it"
    }
    reporter.reportOn(
        rhs.source,
        ConstraintErrors.INTRANGE_NOT_VERIFIED,
        "Cannot prove this satisfies @IntRange(${target.min}, ${target.max}): $detail. " +
            "Wrap it in checkIntRange(value, ${target.min}, ${target.max}) to defer the check to runtime.",
        context,
    )
}

// ===========================================================================
// Interval inference over the resolved FIR tree
// ===========================================================================

private fun inferInterval(expr: FirExpression?, session: FirSession): Interval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? Int)?.let { Interval.point(it.toLong()) } ?: Interval.UNKNOWN

    is FirFunctionCall -> inferCall(expr, session)

    // A bare variable read: use its declared @IntRange (a sound invariant, since every
    // write to it is itself checked). Unannotated variables are unknown.
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.intRangeBounds(session) ?: Interval.UNKNOWN

    else -> Interval.UNKNOWN
}

private fun inferCall(call: FirFunctionCall, session: FirSession): Interval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN

    // Escape hatch: checkIntRange(value, lo, hi) guarantees a result within [lo, hi].
    if (callee.callableId == CHECK_INT_RANGE_ID) {
        val lo = (call.arguments.getOrNull(1) as? FirLiteralExpression)?.value as? Int
        val hi = (call.arguments.getOrNull(2) as? FirLiteralExpression)?.value as? Int
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
        // Any other call: trust an @IntRange on its return type, if it has one.
        else -> callee.returnTypeIntRange(session) ?: Interval.UNKNOWN
    }
}

/** Reads `@IntRange(min, max)` off a variable symbol as an [Interval], or null if absent. */
private fun FirVariableSymbol<*>.intRangeBounds(session: FirSession): Interval? {
    val annotation = resolvedAnnotationsWithArguments.firstOrNull {
        it.toAnnotationClassId(session) == INT_RANGE_CLASS_ID
    } ?: return null
    val min = annotation.intArgument("min") ?: return null
    val max = annotation.intArgument("max") ?: return null
    return Interval(min.toLong(), max.toLong())
}

/** Reads `@IntRange(min, max)` off a callable's return type as an [Interval], or null. */
private fun FirCallableSymbol<*>.returnTypeIntRange(session: FirSession): Interval? {
    val annotation = resolvedReturnType.customAnnotations.firstOrNull {
        it.toAnnotationClassId(session) == INT_RANGE_CLASS_ID
    } ?: return null
    val min = annotation.intArgument("min") ?: return null
    val max = annotation.intArgument("max") ?: return null
    return Interval(min.toLong(), max.toLong())
}

private fun FirAnnotation.intArgument(name: String): Int? =
    (argumentMapping.mapping[Name.identifier(name)] as? FirLiteralExpression)?.value as? Int
