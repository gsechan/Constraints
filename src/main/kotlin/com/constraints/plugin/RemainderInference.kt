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
// Remainder inference (for @DivisibleBy) -- maps an expression to its remainder modulo a divisor.
//
// Uses *floored* modulo (Kotlin's `.mod`), i.e. true congruence classes, so residues compose
// soundly through +, -, *: (a OP b) mod n == ((a mod n) OP (b mod n)) mod n. This is why the
// constraint must use floored modulo at runtime too -- `%` would not be sound here.
// ===========================================================================

/**
 * The remainder of [expr] modulo [divisor] (floored, in `[0, divisor)`) if it can be determined
 * statically, else null.
 */
internal fun inferRemainder(expr: FirExpression?, divisor: Long, session: FirSession): Long? {
    if (divisor == 0L) return null
    return when (expr) {
        is FirLiteralExpression -> (expr.value as? Number)?.toLong()?.mod(divisor)

        // A bare variable read: if it is declared @DivisibleBy(d', r') and `divisor` divides d',
        // then `value ≡ r' (mod divisor)` (a sound invariant, since every write to it is checked).
        is FirPropertyAccessExpression -> {
            val known = expr.calleeReference.toResolvedVariableSymbol()?.divisibleBy(session) ?: return null
            if (known.divisor != 0L && known.divisor % divisor == 0L) known.remainder.mod(divisor) else null
        }

        // `a++` / `a += ...` desugar so the variable read becomes this reference wrapper.
        is FirDesugaredAssignmentValueReferenceExpression -> inferRemainder(expr.expressionRef.value, divisor, session)

        is FirFunctionCall -> inferRemainderCall(expr, divisor, session)

        else -> null
    }
}

private fun inferRemainderCall(call: FirFunctionCall, divisor: Long, session: FirSession): Long? {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
    val receiver = inferRemainder(call.dispatchReceiver ?: call.explicitReceiver, divisor, session)
    val arg = inferRemainder(call.arguments.firstOrNull(), divisor, session)
    return when (callee.name.asString()) {
        "inc" -> remainderOp(receiver, 1L, divisor) { a, b -> Math.addExact(a, b) }
        "dec" -> remainderOp(receiver, 1L, divisor) { a, b -> Math.subtractExact(a, b) }
        "unaryMinus" -> remainderOp(0L, receiver, divisor) { a, b -> Math.subtractExact(a, b) }
        "plus" -> remainderOp(receiver, arg, divisor) { a, b -> Math.addExact(a, b) }
        "minus" -> remainderOp(receiver, arg, divisor) { a, b -> Math.subtractExact(a, b) }
        "times" -> remainderOp(receiver, arg, divisor) { a, b -> Math.multiplyExact(a, b) }
        // div and rem do not preserve congruence. For any other call, trust a @DivisibleBy on its
        // return type: if `divisor` divides the return type's divisor, the residue carries over.
        else -> {
            val known = callee.returnTypeDivisibleBy(session)
            if (known != null && known.divisor != 0L && known.divisor % divisor == 0L) {
                known.remainder.mod(divisor)
            } else {
                null
            }
        }
    }
}

/**
 * Combines two known residues with [op], reducing the result modulo [divisor] (floored). Returns
 * null if either residue is unknown, or if [op] overflows Long (possible for a large Long divisor)
 * -- so an unprovable case soundly falls back to `checkConstraint`. internal for unit testing.
 */
internal fun remainderOp(a: Long?, b: Long?, divisor: Long, op: (Long, Long) -> Long): Long? {
    if (a == null || b == null) return null
    return try {
        op(a, b).mod(divisor)
    } catch (e: ArithmeticException) {
        null
    }
}
