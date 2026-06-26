package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.resolvedType

// ===========================================================================
// Size constraint (@Size). One annotation for the size of any sized value -- a
// CharSequence's length, a Collection/Map's size, or an array's size. The bounds
// are an [Interval]; the per-category FIR-tree walks live in StringLengthInference,
// CollectionSizeInference, and ArraySizeInference, dispatched by [inferSize].
// ===========================================================================

/** A size constraint: its min/max bounds expressed as an [Interval]. */
internal class SizeTarget(val interval: Interval)

internal fun readSizeTarget(annotation: FirAnnotation): SizeTarget? {
    val min = annotation.longArgument("min") ?: return null
    val max = annotation.longArgument("max") ?: return null
    return SizeTarget(Interval(min, max))
}

internal fun FirVariableSymbol<*>.sizeTarget(session: FirSession): SizeTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.sizeTarget(session) }

internal fun FirCallableSymbol<*>.returnTypeSize(session: FirSession): SizeTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.sizeTarget(session) }

internal fun FirAnnotation.sizeTarget(session: FirSession): SizeTarget? {
    if (toAnnotationClassId(session) == SIZE_CLASS_ID) return readSizeTarget(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        if (it.toAnnotationClassId(session) == SIZE_CLASS_ID) readSizeTarget(it) else null
    }
}

/**
 * Infers the size [Interval] of [expr], routing to the right per-category inference by the
 * expression's resolved type: arrays to [inferArraySize], CharSequences to [inferStringLength],
 * everything else (Collection, Map, …) to [inferCollectionSize]. Routing matters because `+` and
 * friends mean different things per category; a bare variable read works regardless, since each
 * inference reads the variable's own `@Size`.
 */
internal fun inferSize(expr: FirExpression?, session: FirSession): Interval {
    if (expr == null) return Interval.UNKNOWN
    if (expr is FirDesugaredAssignmentValueReferenceExpression) return inferSize(expr.expressionRef.value, session)
    val classId = expr.resolvedType.classId
    return when {
        classId in ARRAY_CLASS_IDS -> inferArraySize(expr, session)
        classId in CHAR_SEQUENCE_CLASS_IDS -> inferStringLength(expr, session)
        else -> inferCollectionSize(expr, session)
    }
}

/** Reports an error unless the size of [rhs] is provably within [target]'s bounds. */
internal fun verifySize(rhs: FirExpression, target: SizeTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferSize(rhs, context.session)
    if (inferred.subsetOf(bounds)) return
    val label = "@Size(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its size cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "Size [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the size ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}
