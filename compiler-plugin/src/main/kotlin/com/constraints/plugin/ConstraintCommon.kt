package com.constraints.plugin

import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// ===========================================================================
// Shared identifiers and helpers used across the per-constraint checker files.
// ===========================================================================

internal val BYTE_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ByteRange"))
internal val SHORT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ShortRange"))
internal val INT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("IntRange"))
internal val LONG_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("LongRange"))
internal val FLOAT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("FloatRange"))
internal val DOUBLE_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("DoubleRange"))
internal val BYTE_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ByteDivisibleBy"))
internal val SHORT_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ShortDivisibleBy"))
internal val DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("DivisibleBy"))
internal val LONG_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("LongDivisibleBy"))
internal val STRING_LENGTH_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("StringLength"))
internal val COLLECTION_SIZE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("CollectionSize"))
internal val CONSTRAINT_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("Constraint"))
internal val ELEMENT_CONSTRAINT_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ElementConstraint"))
internal val CHECK_CONSTRAINT_ID = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

/**
 * Constraints the checker analyzes statically (interval for ranges, residue for divisibility, etc.).
 * They are detected by their own class id, so they are excluded from the generic transfer-only
 * fallback that applies to every other `@Constraint` (see [runtimeConstraintKey]).
 */
internal val BUILTIN_ANALYZED = setOf(
    BYTE_RANGE_CLASS_ID, SHORT_RANGE_CLASS_ID, INT_RANGE_CLASS_ID, LONG_RANGE_CLASS_ID,
    FLOAT_RANGE_CLASS_ID, DOUBLE_RANGE_CLASS_ID,
    BYTE_DIVISIBLE_BY_CLASS_ID, SHORT_DIVISIBLE_BY_CLASS_ID, DIVISIBLE_BY_CLASS_ID, LONG_DIVISIBLE_BY_CLASS_ID,
    STRING_LENGTH_CLASS_ID, COLLECTION_SIZE_CLASS_ID,
)

/** True if [expr] is a bare 1-arg `checkConstraint(value)` escape hatch. */
internal fun isCheckConstraints(expr: FirExpression?): Boolean =
    expr is FirFunctionCall &&
        expr.calleeReference.toResolvedNamedFunctionSymbol()?.callableId == CHECK_CONSTRAINT_ID &&
        expr.arguments.size == 1

/** Reads a Long-valued annotation argument by name (FIR stores integer literals as Long). */
internal fun FirAnnotation.longArgument(name: String): Long? {
    val expr = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == name }?.value
    return ((expr as? FirLiteralExpression)?.value as? Number)?.toLong()
}
