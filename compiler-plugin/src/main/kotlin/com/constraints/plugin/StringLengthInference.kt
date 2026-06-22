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
// String length inference -- maps a CharSequence expression to the [Interval]
// of lengths it can have, for proving `@StringLength`. Lengths are non-negative
// integers that fit in Int, so we reuse [NumericDomain.INT] for the arithmetic.
//
// Proven cases:
//   - String literals: length is known exactly.
//   - Variable reads: use the declared @StringLength bounds.
//   - Concatenation (`+`): the result length is the sum of the two operand lengths.
//   - Return type: trust a @StringLength on the callee's return type.
// ===========================================================================

/**
 * Infers the length [Interval] of [expr]. Returns [Interval.UNKNOWN] for anything not
 * statically provable (dynamic input, interpolation, other function calls).
 */
internal fun inferStringLength(expr: FirExpression?, session: FirSession): Interval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? String)?.let { Interval.point(it.length.toLong()) } ?: Interval.UNKNOWN

    is FirFunctionCall -> inferStringLengthCall(expr, session)

    // A bare variable read: use its declared @StringLength bounds (sound invariant -- every write
    // is checked, so the variable's length always lies within its declared range).
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.stringLengthTarget(session)?.interval ?: Interval.UNKNOWN

    // `a++` / `a += ...` desugar so the variable read becomes this reference wrapper.
    is FirDesugaredAssignmentValueReferenceExpression ->
        inferStringLength(expr.expressionRef.value, session)

    else -> Interval.UNKNOWN
}

private fun inferStringLengthCall(call: FirFunctionCall, session: FirSession): Interval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN
    // String concatenation: `a + b` → length is len(a) + len(b), using Int arithmetic.
    if (callee.name.asString() == "plus") {
        val receiverLen = inferStringLength(call.dispatchReceiver ?: call.explicitReceiver, session)
        val argLen = inferStringLength(call.arguments.firstOrNull(), session)
        return NumericDomain.INT.plus(receiverLen, argLen)
    }
    // Any other call: trust a @StringLength on its return type, if it has one.
    return callee.returnTypeStringLength(session)?.interval ?: Interval.UNKNOWN
}
