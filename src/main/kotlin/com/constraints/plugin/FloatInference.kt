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
// Floating point interval inference -- maps a Float/Double expression to the
// [DoubleInterval] of values it can take, for proving `@FloatRange` / `@DoubleRange`.
//
// Unlike integer inference, there is no domain to thread: all arithmetic is done
// directly on [DoubleInterval] operators, which widen to UNKNOWN when bounds become
// non-finite. The user accepts that minor floating point rounding at boundaries may
// occasionally prove a value that rounds just outside the range (or miss one that
// rounds just inside); anything genuinely unresolvable falls back to `checkConstraint`.
// ===========================================================================

internal fun inferDoubleInterval(expr: FirExpression?, session: FirSession): DoubleInterval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? Number)?.let { DoubleInterval.point(it.toDouble()) } ?: DoubleInterval.UNKNOWN

    is FirFunctionCall -> inferDoubleCall(expr, session)

    // A bare variable read: use its declared @FloatRange / @DoubleRange bounds.
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.doubleRangeTarget(session)?.interval ?: DoubleInterval.UNKNOWN

    // `a++` / `a += ...` desugar so the variable read becomes this reference wrapper.
    is FirDesugaredAssignmentValueReferenceExpression ->
        inferDoubleInterval(expr.expressionRef.value, session)

    else -> DoubleInterval.UNKNOWN
}

private fun inferDoubleCall(call: FirFunctionCall, session: FirSession): DoubleInterval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return DoubleInterval.UNKNOWN
    val receiver = inferDoubleInterval(call.dispatchReceiver ?: call.explicitReceiver, session)
    fun arg() = inferDoubleInterval(call.arguments.firstOrNull(), session)
    return when (callee.name.asString()) {
        "inc" -> receiver + DoubleInterval.point(1.0)
        "dec" -> receiver - DoubleInterval.point(1.0)
        "unaryMinus" -> -receiver
        "plus" -> receiver + arg()
        "minus" -> receiver - arg()
        "times" -> receiver * arg()
        "div" -> receiver / arg()
        // rem is excluded: floating point remainder is complex (see Math.IEEEremainder vs %)
        // Any other call: trust a @FloatRange / @DoubleRange on its return type, if it has one.
        else -> callee.returnTypeDoubleRange(session)?.interval ?: DoubleInterval.UNKNOWN
    }
}
