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
// Array-size constraint (@ArraySize). Tracks the size of an array (Array<T> or a
// primitive array alias) as an [Interval]; the FIR-tree walk lives in
// ArraySizeInference.kt. Reuses [LengthTarget] / [readLengthTarget] from
// StringLengthConstraint.kt (both express a non-negative count as an Interval).
// ===========================================================================

internal fun FirVariableSymbol<*>.arraySizeTarget(session: FirSession): LengthTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.arraySizeTarget(session) }

internal fun FirCallableSymbol<*>.returnTypeArraySize(session: FirSession): LengthTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.arraySizeTarget(session) }

internal fun FirAnnotation.arraySizeTarget(session: FirSession): LengthTarget? {
    if (toAnnotationClassId(session) == ARRAY_SIZE_CLASS_ID) return readLengthTarget(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        if (it.toAnnotationClassId(session) == ARRAY_SIZE_CLASS_ID) readLengthTarget(it) else null
    }
}

/** Reports an error unless the array size of [rhs] is provably within [target]'s bounds. */
internal fun verifyArraySize(rhs: FirExpression, target: LengthTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferArraySize(rhs, context.session)
    if (inferred.subsetOf(bounds)) return
    val label = "@ArraySize(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its size cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "Array size [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the size ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}
