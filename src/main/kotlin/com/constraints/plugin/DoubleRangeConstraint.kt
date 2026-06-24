package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// Floating-point range constraints (@FloatRange / @DoubleRange).
//
// Uses DoubleInterval rather than Interval (Long-based) -- Float/Double bounds can't be
// represented losslessly in Long. The FIR-tree walk (with arithmetic) lives in FloatInference.kt.
// ===========================================================================

/** A Float/Double range constraint: its bounds and a display label. */
internal class DoubleRangeTarget(val interval: DoubleInterval, val label: String)

/** The floating-point range constraint on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.doubleRangeTarget(session: FirSession): DoubleRangeTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.doubleRangeTarget(session) }

/** The floating-point range constraint on this callable's return type, directly or via an alias. */
internal fun FirCallableSymbol<*>.returnTypeDoubleRange(session: FirSession): DoubleRangeTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.doubleRangeTarget(session) }

/** The DoubleRangeTarget this annotation carries (directly or via alias), or null. */
internal fun FirAnnotation.doubleRangeTarget(session: FirSession): DoubleRangeTarget? {
    doubleRangeTargetFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        doubleRangeTargetFor(it.toAnnotationClassId(session), it)
    }
}

private fun doubleRangeTargetFor(classId: ClassId?, range: FirAnnotation): DoubleRangeTarget? = when (classId) {
    FLOAT_RANGE_CLASS_ID -> readDoubleInterval(range)?.let { DoubleRangeTarget(it, "@FloatRange") }
    DOUBLE_RANGE_CLASS_ID -> readDoubleInterval(range)?.let { DoubleRangeTarget(it, "@DoubleRange") }
    else -> null
}

private fun readDoubleInterval(range: FirAnnotation): DoubleInterval? {
    val min = range.doubleArgument("min") ?: return null
    val max = range.doubleArgument("max") ?: return null
    return DoubleInterval(min, max)
}

private fun FirAnnotation.doubleArgument(name: String): Double? {
    val expr = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == name }?.value
    return ((expr as? FirLiteralExpression)?.value as? Number)?.toDouble()
}

/**
 * Reports an error unless [rhs] is provably within [target]'s floating point bounds. Literals,
 * same-range transfers, and arithmetic (`+ - * / inc dec unaryMinus`) are proven where possible.
 * If arithmetic overflows to Infinity the result widens to UNKNOWN and `checkConstraint` is
 * required. Minor rounding at boundaries is accepted as a known trade-off.
 */
internal fun verifyDoubleRange(rhs: FirExpression, target: DoubleRangeTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferDoubleInterval(rhs, context.session)
    if (inferred.subsetOf(bounds)) return // statically proven in range -> no runtime check needed

    val label = "${target.label}(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its range cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "Value range [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}
