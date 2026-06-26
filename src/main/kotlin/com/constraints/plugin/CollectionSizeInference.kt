package com.constraints.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// ===========================================================================
// Collection-size inference -- maps an immutable Collection expression to the
// [Interval] of sizes it can have, for proving `@CollectionSize`. Reuses
// [NumericDomain.INT] since collection sizes fit in a non-negative Int.
//
// Proven cases:
//   - Known-size factory calls (listOf, setOf, emptyList, …): exact count.
//   - `collection + element` (single, non-collection): result = receiver.size + 1 (exact).
//   - `collection + otherCollection` (known @CollectionSize): sum of sizes.
//   - `collection - element` (single): removes first occurrence → [max(0, min-1), max].
//   - `collection - otherCollection` (known @CollectionSize): [max(0, min-other.max), max].
//   - Variable reads: use the declared @CollectionSize bounds.
//   - Callee return type annotated with @CollectionSize: trust those bounds.
//
// For `+` and `-`, the inference checks whether the argument is a collection (by its type's
// ClassId) or a single element, which enables tighter bounds than the unified approach.
// ===========================================================================

// Factory functions that always return an empty collection.
private val EMPTY_FACTORIES = setOf(
    "kotlin.collections.emptyList",
    "kotlin.collections.emptySet",
    "kotlin.collections.emptyMap",
    "kotlin.collections.emptySequence",
)

// Vararg factory functions whose size equals the number of non-spread arguments.
private val VARARG_FACTORIES = setOf(
    "kotlin.collections.listOf",
    "kotlin.collections.setOf",
    "kotlin.collections.mutableListOf",
    "kotlin.collections.mutableSetOf",
    "kotlin.collections.arrayListOf",
    "kotlin.collections.linkedSetOf",
    "kotlin.collections.sortedSetOf",
    "kotlin.collections.mapOf",
    "kotlin.collections.mutableMapOf",
    "kotlin.collections.linkedMapOf",
    "kotlin.collections.sortedMapOf",
    "kotlin.collections.hashSetOf",
    "kotlin.collections.hashMapOf",
)

// Known collection ClassIds used to distinguish `list + element` from `list + otherList`.
private val COLLECTION_CLASS_IDS = setOf(
    ClassId(FqName("kotlin.collections"), Name.identifier("Iterable")),
    ClassId(FqName("kotlin.collections"), Name.identifier("MutableIterable")),
    ClassId(FqName("kotlin.collections"), Name.identifier("Collection")),
    ClassId(FqName("kotlin.collections"), Name.identifier("MutableCollection")),
    ClassId(FqName("kotlin.collections"), Name.identifier("List")),
    ClassId(FqName("kotlin.collections"), Name.identifier("MutableList")),
    ClassId(FqName("kotlin.collections"), Name.identifier("Set")),
    ClassId(FqName("kotlin.collections"), Name.identifier("MutableSet")),
    ClassId(FqName("kotlin.collections"), Name.identifier("Map")),
    ClassId(FqName("kotlin.collections"), Name.identifier("MutableMap")),
    ClassId(FqName("java.util"), Name.identifier("Collection")),
    ClassId(FqName("java.util"), Name.identifier("List")),
    ClassId(FqName("java.util"), Name.identifier("Set")),
    ClassId(FqName("java.util"), Name.identifier("Map")),
    ClassId(FqName("java.util"), Name.identifier("ArrayList")),
    ClassId(FqName("java.util"), Name.identifier("LinkedList")),
    ClassId(FqName("java.util"), Name.identifier("HashSet")),
    ClassId(FqName("java.util"), Name.identifier("LinkedHashSet")),
    ClassId(FqName("java.util"), Name.identifier("HashMap")),
    ClassId(FqName("java.util"), Name.identifier("LinkedHashMap")),
    ClassId(FqName("java.util"), Name.identifier("TreeSet")),
    ClassId(FqName("java.util"), Name.identifier("TreeMap")),
)

/**
 * Infers the size [Interval] of the Collection [expr]. Returns [Interval.UNKNOWN]
 * for anything not statically provable, requiring `checkConstraint`.
 */
internal fun inferCollectionSize(expr: FirExpression?, session: FirSession): Interval = when (expr) {
    is FirFunctionCall -> inferCollectionSizeCall(expr, session)

    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.collectionSizeTarget(session)?.interval ?: Interval.UNKNOWN

    is FirDesugaredAssignmentValueReferenceExpression ->
        inferCollectionSize(expr.expressionRef.value, session)

    else -> Interval.UNKNOWN
}

private fun inferCollectionSizeCall(call: FirFunctionCall, session: FirSession): Interval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN
    val calleeName = callee.name.asString()

    if (calleeName == "plus") {
        val receiverSize = inferCollectionSize(call.dispatchReceiver ?: call.explicitReceiver, session)
        if (receiverSize.isUnknown) return Interval.UNKNOWN
        val arg = call.arguments.firstOrNull() ?: return Interval.UNKNOWN
        val argSize = inferCollectionSize(arg, session)
        return when {
            // Arg has known size (annotated or factory): sum exactly.
            !argSize.isUnknown -> NumericDomain.INT.plus(receiverSize, argSize)
            // Arg is a collection with unknown size: can't bound the result.
            arg.isCollectionType() -> Interval.UNKNOWN
            // Arg is a single element: always adds exactly 1.
            else -> NumericDomain.INT.plus(receiverSize, Interval.point(1))
        }
    }

    if (calleeName == "minus") {
        val receiverSize = inferCollectionSize(call.dispatchReceiver ?: call.explicitReceiver, session)
        if (receiverSize.isUnknown) return Interval.UNKNOWN
        val arg = call.arguments.firstOrNull()
        val argSize = if (arg != null) inferCollectionSize(arg, session) else Interval.UNKNOWN
        return when {
            // Removing a known-size collection: [max(0, min-other.max), max].
            !argSize.isUnknown ->
                NumericDomain.INT.of(maxOf(0, receiverSize.min - argSize.max), receiverSize.max)
            // Removing an unknown-size collection: can remove everything.
            arg?.isCollectionType() == true ->
                NumericDomain.INT.of(0, receiverSize.max)
            // Removing a single element: Kotlin's minus removes the FIRST occurrence only
            // → at most 1 removed → [max(0, min-1), max].
            else ->
                NumericDomain.INT.of(maxOf(0, receiverSize.min - 1), receiverSize.max)
        }
    }

    val fqName = callee.callableId.let { id ->
        if (id.className == null) "${id.packageName}.${id.callableName}"
        else "${id.packageName}.${id.className}.${id.callableName}"
    }

    if (fqName in EMPTY_FACTORIES) return Interval.point(0)

    if (fqName in VARARG_FACTORIES) {
        if (call.arguments.isEmpty()) return Interval.point(0)
        // In FIR K2, the compiler packages all vararg elements into a single
        // FirVarargArgumentsExpression rather than exposing them as N separate arguments.
        val varargExpr = call.arguments.singleOrNull() as? FirVarargArgumentsExpression
        if (varargExpr != null) {
            // No spread (*arr) elements → size is the exact element count.
            if (varargExpr.arguments.none { it is FirSpreadArgumentExpression }) {
                return Interval.point(varargExpr.arguments.size.toLong())
            }
            return Interval.UNKNOWN // spread present → count unknown
        }
        // Fallback: arguments already flat (shouldn't happen for vararg factories).
        if (call.arguments.none { it is FirSpreadArgumentExpression }) {
            return Interval.point(call.arguments.size.toLong())
        }
    }

    return callee.returnTypeCollectionSize(session)?.interval ?: Interval.UNKNOWN
}

/**
 * True if this expression's resolved type is a known collection interface or class. Used to
 * distinguish `list + element` (single element → always +1) from `list + otherList`
 * (unknown collection → cannot bound the size). Custom collection types not in
 * [COLLECTION_CLASS_IDS] are treated as single elements (conservative for the `+1` direction
 * but safe -- the user can annotate them with `@CollectionSize` to get tighter bounds).
 */
internal fun FirExpression.isCollectionType(): Boolean =
    resolvedType.classId in COLLECTION_CLASS_IDS
