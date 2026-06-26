package com.constraints.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// ===========================================================================
// Array-size inference -- maps an array expression to the [Interval] of sizes it
// can have, for proving `@Size`. Mirrors CollectionSizeInference but for
// Array<T> and the primitive array aliases. Reuses [NumericDomain.INT] since
// array sizes fit in a non-negative Int.
//
// Proven cases:
//   - Known-size factory calls (arrayOf, intArrayOf, …, emptyArray): exact count.
//   - arrayOfNulls(n) with a statically-known size n: that size (or range).
//   - `array + element` (single, non-array/collection): result = receiver.size + 1 (exact).
//   - `array + otherArray` / `array + collection` (known size): sum of sizes.
//   - Variable reads: use the declared @Size bounds.
//   - Callee return type annotated with @Size: trust those bounds.
// ===========================================================================

// Vararg array factories whose size equals the number of non-spread arguments.
private val VARARG_ARRAY_FACTORIES = setOf(
    "kotlin.arrayOf",
    "kotlin.intArrayOf",
    "kotlin.doubleArrayOf",
    "kotlin.longArrayOf",
    "kotlin.floatArrayOf",
    "kotlin.shortArrayOf",
    "kotlin.byteArrayOf",
    "kotlin.charArrayOf",
    "kotlin.booleanArrayOf",
)

// Array<T> plus the eight primitive array aliases -- used to tell `array + element`
// from `array + otherArray`, and by inferSize to route to array inference.
internal val ARRAY_CLASS_IDS = setOf(
    ClassId(FqName("kotlin"), Name.identifier("Array")),
    ClassId(FqName("kotlin"), Name.identifier("IntArray")),
    ClassId(FqName("kotlin"), Name.identifier("DoubleArray")),
    ClassId(FqName("kotlin"), Name.identifier("LongArray")),
    ClassId(FqName("kotlin"), Name.identifier("FloatArray")),
    ClassId(FqName("kotlin"), Name.identifier("ShortArray")),
    ClassId(FqName("kotlin"), Name.identifier("ByteArray")),
    ClassId(FqName("kotlin"), Name.identifier("CharArray")),
    ClassId(FqName("kotlin"), Name.identifier("BooleanArray")),
)

/**
 * Infers the size [Interval] of the array [expr]. Returns [Interval.UNKNOWN] for
 * anything not statically provable, requiring `checkConstraint`.
 */
internal fun inferArraySize(expr: FirExpression?, session: FirSession): Interval = when (expr) {
    is FirFunctionCall -> inferArraySizeCall(expr, session)

    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.sizeTarget(session)?.interval ?: Interval.UNKNOWN

    is FirDesugaredAssignmentValueReferenceExpression ->
        inferArraySize(expr.expressionRef.value, session)

    else -> Interval.UNKNOWN
}

private fun inferArraySizeCall(call: FirFunctionCall, session: FirSession): Interval {
    // Array constructors -- Array(n) { … }, IntArray(n), IntArray(n) { … }, etc. The first argument
    // is the size. Constructor calls don't resolve to a *named function* symbol, so handle them
    // before the factory/operator logic below.
    if (call.isArrayConstructorCall()) {
        val sizeArg = inferInterval(call.arguments.firstOrNull(), session, NumericDomain.INT)
        return if (!sizeArg.isUnknown && sizeArg.min >= 0) sizeArg else Interval.UNKNOWN
    }

    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN
    val calleeName = callee.name.asString()

    if (calleeName == "plus") {
        val receiverSize = inferArraySize(call.dispatchReceiver ?: call.explicitReceiver, session)
        if (receiverSize.isUnknown) return Interval.UNKNOWN
        val arg = call.arguments.firstOrNull() ?: return Interval.UNKNOWN
        // The argument may be another array, a collection, or a single element.
        val argArraySize = inferArraySize(arg, session)
        val argSize = if (!argArraySize.isUnknown) argArraySize else inferCollectionSize(arg, session)
        return when {
            // Arg has a known size (array or collection): sum exactly.
            !argSize.isUnknown -> NumericDomain.INT.plus(receiverSize, argSize)
            // Arg is an array/collection of unknown size: can't bound the result.
            arg.isArrayType() || arg.isCollectionType() -> Interval.UNKNOWN
            // Arg is a single element: always adds exactly 1.
            else -> NumericDomain.INT.plus(receiverSize, Interval.point(1))
        }
    }

    val fqName = callee.callableId.let { id ->
        if (id.className == null) "${id.packageName}.${id.callableName}"
        else "${id.packageName}.${id.className}.${id.callableName}"
    }

    if (fqName == "kotlin.emptyArray") return Interval.point(0)

    if (fqName in VARARG_ARRAY_FACTORIES) {
        if (call.arguments.isEmpty()) return Interval.point(0)
        // In FIR K2 the vararg elements are packed into one FirVarargArgumentsExpression for
        // multiple args, but a single arg comes through unwrapped -- handle both.
        val elements = (call.arguments.singleOrNull() as? FirVarargArgumentsExpression)?.arguments ?: call.arguments
        if (elements.none { it is FirSpreadArgumentExpression }) return Interval.point(elements.size.toLong())
        return Interval.UNKNOWN // spread present → count unknown
    }

    // arrayOfNulls(n): size is n when n is statically known (a literal or a ranged value).
    if (fqName == "kotlin.arrayOfNulls") {
        val sizeArg = inferInterval(call.arguments.firstOrNull(), session, NumericDomain.INT)
        if (!sizeArg.isUnknown && sizeArg.min >= 0) return sizeArg
        return Interval.UNKNOWN
    }

    return callee.returnTypeSize(session)?.interval ?: Interval.UNKNOWN
}

/** True if this expression's resolved type is `Array<T>` or a primitive array alias. */
private fun FirExpression.isArrayType(): Boolean =
    resolvedType.classId in ARRAY_CLASS_IDS

/**
 * True if this call is a constructor of an array type (`Array`/`IntArray`/…). The classId guard
 * keeps a *function* that merely returns an array (e.g. `buildIntArray(seed)`) out -- only an actual
 * array constructor has its size as the first argument.
 */
private fun FirFunctionCall.isArrayConstructorCall(): Boolean =
    calleeReference.toResolvedCallableSymbol() is FirConstructorSymbol && resolvedType.classId in ARRAY_CLASS_IDS
