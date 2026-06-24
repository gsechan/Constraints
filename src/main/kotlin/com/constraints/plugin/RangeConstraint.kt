package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// Integer range constraints (@ByteRange / @ShortRange / @IntRange / @LongRange).
// Reasoning is over the [Interval] lattice (see Interval.kt / NumericDomain.kt);
// the FIR-tree walk lives in IntervalInference.kt.
// ===========================================================================

/** A range constraint plus the numeric domain it lives in (so interval math clamps to the right bounds). */
internal class RangeTarget(val interval: Interval, val domain: NumericDomain, val label: String)

/** The range constraint (`@IntRange` or `@LongRange`) on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.rangeTarget(session: FirSession): RangeTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.rangeTarget(session) }

/** The range constraint on this callable's return type, directly or via an alias. */
internal fun FirCallableSymbol<*>.returnTypeRange(session: FirSession): RangeTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.rangeTarget(session) }

/**
 * The range constraint this annotation carries: `@IntRange`/`@LongRange` directly, or -- for an
 * alias such as `@PositiveInt` -- a range meta-annotation on the annotation's own declaration.
 */
private fun FirAnnotation.rangeTarget(session: FirSession): RangeTarget? {
    rangeTargetFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        rangeTargetFor(it.toAnnotationClassId(session), it)
    }
}

private fun rangeTargetFor(classId: ClassId?, range: FirAnnotation): RangeTarget? = when (classId) {
    BYTE_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.BYTE, "@ByteRange") }
    SHORT_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.SHORT, "@ShortRange") }
    INT_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.INT, "@IntRange") }
    LONG_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.LONG, "@LongRange") }
    else -> null
}

private fun readInterval(range: FirAnnotation): Interval? {
    val min = range.longArgument("min") ?: return null
    val max = range.longArgument("max") ?: return null
    return Interval(min, max)
}

/** Reports an error unless [rhs]'s inferred interval is provably within [target]. */
internal fun verifyRange(rhs: FirExpression, target: RangeTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    // A possible divide-by-zero anywhere in the expression is a hard error in its
    // own right -- report it and stop (the range check would just add noise).
    if (reportDivisionByZero(rhs, target.domain, context, reporter)) return
    // Single-arg `checkConstraint(value)`: explicit escape hatch; the IR backend fills its bounds
    // from `target`, so accept it here without inferring a range.
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferInterval(rhs, context.session, target.domain)
    if (inferred.subsetOf(bounds)) return // statically proven in range -> no runtime check needed

    val label = "${target.label}(${bounds.min}, ${bounds.max})"
    val message = if (inferred.overlaps(bounds)) {
        // Partial overlap (or an unknown range): some values would be valid, so a
        // runtime check is the right escape hatch.
        val range = if (inferred.isUnknown) {
            "its range cannot be determined statically"
        } else {
            "its range [${inferred.min}, ${inferred.max}] is not fully within it"
        }
        "Cannot prove this satisfies $label: $range. Wrap it in checkConstraint(value) to check at runtime."
    } else {
        // Disjoint ranges: the value can never be valid, so a runtime check would
        // always fail -- report it as a definite mismatch instead.
        "Value range [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}

/**
 * Walks [expr] for any integer division whose divisor's range is known to include 0,
 * reporting a divide-by-zero error for each such division. Returns true if any fired.
 */
private fun reportDivisionByZero(expr: FirExpression?, domain: NumericDomain, context: CheckerContext, reporter: DiagnosticReporter): Boolean {
    when (expr) {
        is FirDesugaredAssignmentValueReferenceExpression ->
            return reportDivisionByZero(expr.expressionRef.value, domain, context, reporter)

        is FirFunctionCall -> {
            var found = reportDivisionByZero(expr.dispatchReceiver ?: expr.explicitReceiver, domain, context, reporter)
            for (arg in expr.arguments) {
                if (reportDivisionByZero(arg, domain, context, reporter)) found = true
            }
            val opName = expr.calleeReference.toResolvedNamedFunctionSymbol()?.name?.asString()
            if (opName == "div" || opName == "rem") {
                val divisor = inferInterval(expr.arguments.firstOrNull(), context.session, domain)
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
