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
// Interval inference over the resolved FIR tree -- maps an expression to the range
// ([Interval]) of values it can take, for proving `@IntRange` / `@LongRange`. The
// arithmetic and overflow handling live in [NumericDomain]; this is the FIR-tree walk.
// ===========================================================================

internal fun inferInterval(expr: FirExpression?, session: FirSession, domain: NumericDomain): Interval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? Number)?.let { Interval.point(it.toLong()) } ?: Interval.UNKNOWN

    is FirFunctionCall -> inferCall(expr, session, domain)

    // A bare variable read: use its declared range Unannotated variables are unknown.
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.rangeTarget(session)?.interval ?: Interval.UNKNOWN

    // `a++` / `a += ...` desugar so the variable read becomes this reference wrapper.
    is FirDesugaredAssignmentValueReferenceExpression ->
        inferInterval(expr.expressionRef.value, session, domain)

    else -> Interval.UNKNOWN
}

private fun inferCall(call: FirFunctionCall, session: FirSession, domain: NumericDomain): Interval {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return Interval.UNKNOWN

    // Integer arithmetic: receiver <op> arg, plus inc()/dec() from ++/--. Matched by name; the
    // [domain] (Int or Long) controls overflow/clamping so the over-approximation stays sound.
    val receiver = inferInterval(call.dispatchReceiver ?: call.explicitReceiver, session, domain)
    fun arg() = inferInterval(call.arguments.firstOrNull(), session, domain)
    return when (callee.name.asString()) {
        "inc" -> domain.plus(receiver, Interval.point(1))
        "dec" -> domain.minus(receiver, Interval.point(1))
        "unaryMinus" -> domain.minus(Interval.point(0), receiver)
        "plus" -> domain.plus(receiver, arg())
        "minus" -> domain.minus(receiver, arg())
        "times" -> domain.times(receiver, arg())
        "div" -> domain.div(receiver, arg())
        "rem" -> domain.rem(receiver, arg())
        // Any other call: trust a range on its return type, if it has one.
        else -> callee.returnTypeRange(session)?.interval ?: Interval.UNKNOWN
    }
}
