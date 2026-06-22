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
// Collection-size constraint (@CollectionSize). Tracks the size of a Collection
// as an [Interval]; the FIR-tree walk lives in CollectionSizeInference.kt.
// Reuses [LengthTarget] / [readLengthTarget] from StringLengthConstraint.kt.
// ===========================================================================

internal fun FirVariableSymbol<*>.collectionSizeTarget(session: FirSession): LengthTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.collectionSizeTarget(session) }

internal fun FirCallableSymbol<*>.returnTypeCollectionSize(session: FirSession): LengthTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.collectionSizeTarget(session) }

internal fun FirAnnotation.collectionSizeTarget(session: FirSession): LengthTarget? {
    if (toAnnotationClassId(session) == COLLECTION_SIZE_CLASS_ID) return readLengthTarget(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        if (it.toAnnotationClassId(session) == COLLECTION_SIZE_CLASS_ID) readLengthTarget(it) else null
    }
}

/** Reports an error unless the collection size of [rhs] is provably within [target]'s bounds. */
internal fun verifyCollectionSize(rhs: FirExpression, target: LengthTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferCollectionSize(rhs, context.session)
    if (inferred.subsetOf(bounds)) return
    val label = "@CollectionSize(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its size cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "Collection size [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the size ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}
