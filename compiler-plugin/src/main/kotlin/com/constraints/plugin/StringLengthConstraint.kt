package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations

// ===========================================================================
// String-length constraint (@StringLength). Tracks the length of a CharSequence
// as an [Interval]; the FIR-tree walk lives in StringLengthInference.kt.
//
// [LengthTarget] and [readLengthTarget] are defined here and shared with
// CollectionSizeConstraint.kt (both express a non-negative count as an Interval).
// ===========================================================================

/** A length/size constraint: its min/max bounds expressed as an [Interval]. */
internal class LengthTarget(val interval: Interval)

internal fun readLengthTarget(annotation: FirAnnotation): LengthTarget? {
    val min = annotation.longArgument("min") ?: return null
    val max = annotation.longArgument("max") ?: return null
    return LengthTarget(Interval(min, max))
}

internal fun FirVariableSymbol<*>.stringLengthTarget(session: FirSession): LengthTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.stringLengthTarget(session) }

internal fun FirCallableSymbol<*>.returnTypeStringLength(session: FirSession): LengthTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.stringLengthTarget(session) }

internal fun FirAnnotation.stringLengthTarget(session: FirSession): LengthTarget? {
    if (toAnnotationClassId(session) == STRING_LENGTH_CLASS_ID) return readLengthTarget(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        if (it.toAnnotationClassId(session) == STRING_LENGTH_CLASS_ID) readLengthTarget(it) else null
    }
}

/** Reports an error unless the string length of [rhs] is provably within [target]'s bounds. */
internal fun verifyStringLength(rhs: FirExpression, target: LengthTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferStringLength(rhs, context.session)
    if (inferred.subsetOf(bounds)) return
    val label = "@StringLength(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its length cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "String length [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the length ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}
