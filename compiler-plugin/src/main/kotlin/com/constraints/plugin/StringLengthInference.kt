package com.constraints.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol

// ===========================================================================
// String-length inference -- maps a CharSequence expression to the [Interval]
// of lengths it can have, for proving `@StringLength`. Reuses [NumericDomain.INT].
//
// Proven cases:
//   - String literals: exact length.
//   - Concatenation (`+`): sum of both lengths when both operands are known.
//   - Variable reads: use the declared @StringLength bounds.
//   - Callee return type annotated with @StringLength: trust those bounds.
// ===========================================================================

/**
 * Infers the length [Interval] of the CharSequence [expr]. Returns [Interval.UNKNOWN]
 * for anything not statically provable, requiring `checkConstraint`.
 */
internal fun inferStringLength(expr: FirExpression?, session: FirSession): Interval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? String)?.let { Interval.point(it.length.toLong()) } ?: Interval.UNKNOWN

    is FirFunctionCall -> inferStringLengthCall(expr, session)

    // A bare variable read: use its declared @StringLength bounds.
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.stringLengthTarget(session)?.interval ?: Interval.UNKNOWN

    is FirDesugaredAssignmentValueReferenceExpression ->
        inferStringLength(expr.expressionRef.value, session)

    else -> Interval.UNKNOWN
}

private fun inferStringLengthCall(call: FirFunctionCall, session: FirSession): Interval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN

    // String concatenation: `a + b` → length = len(a) + len(b). Both sides must be known.
    if (callee.name.asString() == "plus") {
        val receiverLen = inferStringLength(call.dispatchReceiver ?: call.explicitReceiver, session)
        val argLen = inferStringLength(call.arguments.firstOrNull(), session)
        if (receiverLen.isUnknown || argLen.isUnknown) return Interval.UNKNOWN
        return NumericDomain.INT.plus(receiverLen, argLen)
    }

    // Trust a @StringLength on the callee's return type.
    return callee.returnTypeStringLength(session)?.interval ?: Interval.UNKNOWN
}
