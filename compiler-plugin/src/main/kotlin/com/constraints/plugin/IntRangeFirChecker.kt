package com.constraints.plugin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirReturnExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val INT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("IntRange"))
private val LONG_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("LongRange"))
private val DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("DivisibleBy"))
private val LONG_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("LongDivisibleBy"))
private val CONSTRAINT_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("Constraint"))
private val CHECK_CONSTRAINT_ID = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

/**
 * Constraints the checker analyzes statically (interval for ranges, residue for divisibility).
 * They are detected by their own class id, so they are excluded from the generic transfer-only
 * fallback that applies to every other `@Constraint`.
 */
private val BUILTIN_ANALYZED = setOf(
    INT_RANGE_CLASS_ID, LONG_RANGE_CLASS_ID, DIVISIBLE_BY_CLASS_ID, LONG_DIVISIBLE_BY_CLASS_ID,
)

// ===========================================================================
// Interval lattice -- the part that does the actual reasoning. Pure Kotlin, so
// it is fully testable on its own and independent of the (more volatile) FIR API.
// ===========================================================================

internal data class Interval(val min: Long, val max: Long) {
    val isUnknown: Boolean get() = this == UNKNOWN

    fun subsetOf(other: Interval): Boolean = min >= other.min && max <= other.max

    /** True if the two ranges share at least one value (so the assignment *could* be valid). */
    fun overlaps(other: Interval): Boolean = min <= other.max && other.min <= max

    companion object {
        /** "Could be anything" -- the top of the lattice; never a subset of a real range. */
        val UNKNOWN = Interval(Long.MIN_VALUE, Long.MAX_VALUE)

        fun point(v: Long) = Interval(v, v)
    }
}

/**
 * The representable range of a constrained value type -- [INT] or [LONG]. Interval arithmetic is
 * done over-approximately in [Long]; a result is widened to [Interval.UNKNOWN] whenever it would
 * overflow `Long` (caught via the `*Exact` helpers) OR leave [min]..[max]. That keeps the analysis
 * sound for both domains: an `Int` value whose math escapes 32 bits would wrap at runtime, and a
 * `Long` value whose math escapes 64 bits would wrap too -- in neither case can we trust the
 * over-approximation, so we forget it.
 */
internal class NumericDomain(val min: Long, val max: Long) {

    fun of(lo: Long, hi: Long): Interval =
        if (lo < min || hi > max) Interval.UNKNOWN else Interval(lo, hi)

    fun plus(a: Interval, b: Interval): Interval =
        if (a.isUnknown || b.isUnknown) Interval.UNKNOWN
        else safe { of(Math.addExact(a.min, b.min), Math.addExact(a.max, b.max)) }

    fun minus(a: Interval, b: Interval): Interval =
        if (a.isUnknown || b.isUnknown) Interval.UNKNOWN
        else safe { of(Math.subtractExact(a.min, b.max), Math.subtractExact(a.max, b.min)) }

    fun times(a: Interval, b: Interval): Interval =
        if (a.isUnknown || b.isUnknown) Interval.UNKNOWN
        else safe {
            val corners = longArrayOf(
                Math.multiplyExact(a.min, b.min), Math.multiplyExact(a.min, b.max),
                Math.multiplyExact(a.max, b.min), Math.multiplyExact(a.max, b.max),
            )
            of(corners.min(), corners.max())
        }

    fun div(a: Interval, b: Interval): Interval {
        if (a.isUnknown || b.isUnknown) return Interval.UNKNOWN
        // If the divisor range includes 0 we can't bound the result (and it might divide by zero).
        if (b.min <= 0 && b.max >= 0) return Interval.UNKNOWN
        // The one integer-division overflow is MIN_VALUE / -1; don't trust the wrapped value.
        if (a.min == Long.MIN_VALUE && b.min <= -1 && b.max >= -1) return Interval.UNKNOWN
        val corners = longArrayOf(a.min / b.min, a.min / b.max, a.max / b.min, a.max / b.max)
        return of(corners.min(), corners.max())
    }

    fun rem(a: Interval, b: Interval): Interval {
        if (a.isUnknown || b.isUnknown) return Interval.UNKNOWN
        // Divisor may be 0 -> can't bound (reported as a divide/modulo-by-zero error elsewhere).
        if (b.min <= 0 && b.max >= 0) return Interval.UNKNOWN
        return safe {
            // `%` keeps the dividend's sign with |result| <= |divisor| - 1, and is also bounded by
            // the dividend. safeAbs widens to UNKNOWN if a divisor bound is MIN_VALUE.
            val maxRemainder = maxOf(safeAbs(b.min), safeAbs(b.max)) - 1
            val lo = if (a.min < 0) maxOf(a.min, -maxRemainder) else 0L
            val hi = if (a.max > 0) minOf(a.max, maxRemainder) else 0L
            of(lo, hi)
        }
    }

    private inline fun safe(block: () -> Interval): Interval =
        try { block() } catch (e: ArithmeticException) { Interval.UNKNOWN }

    /** abs that throws (rather than overflowing to a negative) on MIN_VALUE, so [safe] forgets it. */
    private fun safeAbs(x: Long): Long =
        if (x == Long.MIN_VALUE) throw ArithmeticException("abs overflow") else if (x < 0) -x else x

    companion object {
        val INT = NumericDomain(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        val LONG = NumericDomain(Long.MIN_VALUE, Long.MAX_VALUE)
    }
}

// ===========================================================================
// FIR wiring
// ===========================================================================

class IntRangeFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::IntRangeCheckersExtension
        // Public registration path for the plugin's diagnostics (replaces the
        // internal renderer-map / RootDiagnosticRendererFactory approach).
        registerDiagnosticContainers(ConstraintErrors)
    }
}

class IntRangeCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers = object : DeclarationCheckers() {
        override val propertyCheckers = setOf(IntRangePropertyChecker)
        override val regularClassCheckers = setOf(ConstraintValidatorChecker)
    }

    override val expressionCheckers = object : ExpressionCheckers() {
        override val variableAssignmentCheckers = setOf(IntRangeAssignmentChecker)
        override val returnExpressionCheckers = setOf(IntRangeReturnChecker)
    }
}

/** Initialisation:  `@IntRange(min,max) var x = <initializer>` */
object IntRangePropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val initializer = declaration.initializer ?: return
        verifyConstraints(declaration.symbol, initializer, context, reporter)
    }
}

/** Reassignment, including `a++` / `a--` (which desugar to `a = a.inc()` / `a.dec()`). */
object IntRangeAssignmentChecker : FirVariableAssignmentChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        val symbol = expression.lValue.resolvedVariableSymbolOrNull() ?: return
        verifyConstraints(symbol, expression.rValue, context, reporter)
    }
}

/** Return:  every `return <expr>` from a function with a range / divisibility return type must honor it. */
object IntRangeReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val function = expression.target.labeledElement as? FirFunction ?: return
        function.symbol.returnTypeRange(context.session)?.let { verify(expression.result, it, context, reporter) }
        function.symbol.returnTypeDivisibleBy(context.session)?.let { verifyDivisibility(expression.result, it, context, reporter) }
    }
}

/**
 * Validity of a constraint *definition*: an annotation class meta-annotated `@Constraint(V::class)`
 * must name a validator that is a Kotlin `object` (the plugin runs its singleton instance). A
 * non-object validator is a compile error reported here -- at the definition -- rather than a
 * constraint that silently does nothing at runtime. (`validate` is guaranteed by the
 * `KClass<out Validator<*, *>>` bound, so only object-ness needs checking.)
 */
object ConstraintValidatorChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val constraint = declaration.symbol.resolvedAnnotationsWithArguments.firstOrNull {
            it.toAnnotationClassId(context.session) == CONSTRAINT_CLASS_ID
        } ?: return
        val validatorId = constraint.validatorClassId() ?: return
        val validator = context.session.symbolProvider
            .getClassLikeSymbolByClassId(validatorId) as? FirRegularClassSymbol ?: return
        if (validator.classKind != ClassKind.OBJECT) {
            reporter.reportOn(
                constraint.source,
                ConstraintErrors.CONSTRAINT_VALIDATOR_INVALID,
                "@Constraint validator '${validatorId.shortClassName.asString()}' must be a Kotlin object (a singleton).",
                context,
            )
        }
    }
}

/** Reads the `validator = V::class` argument of a `@Constraint` annotation as the class id of `V`. */
private fun FirAnnotation.validatorClassId(): ClassId? {
    val arg = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == "validator" }?.value
    val getClass = arg as? FirGetClassCall ?: return null
    return (getClass.argument as? FirResolvedQualifier)?.classId
}

/**
 * Verifies every constraint declared on [symbol] against its assigned [rhs].
 *
 * A bare `checkConstraint(value)` defers all of them to runtime and is always accepted.
 * Otherwise each constraint must be proven statically:
 *  - runtime-only `@Constraint`s (everything but the four built-ins below): provable only if
 *    [rhs] is *already known* to satisfy every required constraint -- i.e. it reads a variable
 *    declared with the same constraint (same annotation class and equal arguments). This is sound
 *    because every write to that variable is itself checked, and validators are required to be
 *    pure (a stateless predicate on the value and annotation), so a value that satisfied the
 *    constraint when written still does. A literal or arithmetic result can't be proven against an
 *    opaque validator, so it needs `checkConstraint`.
 *  - `@IntRange` / `@LongRange`: proven by interval inference ([verify]).
 *  - `@DivisibleBy` / `@LongDivisibleBy`: proven by residue inference ([verifyDivisibility]).
 */
private fun verifyConstraints(
    symbol: FirVariableSymbol<*>,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    val required = symbol.runtimeConstraintKeys(context.session)
    val range = symbol.rangeTarget(context.session)
    val divisibility = symbol.divisibleBy(context.session)
    if (required.isEmpty() && range == null && divisibility == null) return

    // The escape hatch satisfies every constraint -- the IR backend injects the checks.
    if (isBareEscapeHatch(rhs)) return

    if (required.isNotEmpty()) {
        val missing = required - knownConstraints(rhs, context.session)
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { it.render() }
            reporter.reportOn(
                rhs.source,
                ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                "Cannot prove this satisfies $names: an opaque validator can't be checked statically. " +
                    "Wrap it in checkConstraint(value) to validate at runtime, or assign from a value already " +
                    "known to satisfy the same constraint (same annotation and arguments).",
                context,
            )
            return // don't also pile on a compile-time-constraint error for the same assignment
        }
    }

    if (range != null) verify(rhs, range, context, reporter)
    if (divisibility != null) verifyDivisibility(rhs, divisibility, context, reporter)
}

/**
 * The set of runtime-only constraints [expr] is *already known* to satisfy: the declared
 * constraints of the variable it reads (a sound invariant, since every write to that variable
 * is checked). Any other expression is known to satisfy nothing.
 */
private fun knownConstraints(expr: FirExpression?, session: FirSession): Set<ConstraintKey> = when (expr) {
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.runtimeConstraintKeys(session) ?: emptySet()

    is FirDesugaredAssignmentValueReferenceExpression ->
        knownConstraints(expr.expressionRef.value, session)

    else -> emptySet()
}

/** Reports an error unless [rhs]'s inferred interval is provably within [target]. */
private fun verify(rhs: FirExpression, target: RangeTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    // A possible divide-by-zero anywhere in the expression is a hard error in its
    // own right -- report it and stop (the range check would just add noise).
    if (reportDivisionByZero(rhs, target.domain, context, reporter)) return
    // Single-arg `checkConstraint(value)`: explicit escape hatch; the IR backend fills its bounds
    // from `target`, so accept it here without inferring a range.
    if (isBareEscapeHatch(rhs)) return
    val bounds = target.interval
    val inferred = inferInterval(rhs, context.session, target.domain)
    if (inferred.subsetOf(bounds)) return // statically proven in range -> no runtime check needed

    val label = "${target.label}(${bounds.min}, ${bounds.max})"
    val message = if (inferred.overlaps(bounds)) {
        // Partial overlap (or an unknown range): some values would be valid, so a
        // runtime check is the right escape hatch.
        val range = if (inferred.isUnknown) {
            "its range cannot be determined statically"
        } else {
            "its range [${inferred.min}, ${inferred.max}] is not fully within it"
        }
        "Cannot prove this satisfies $label: $range. Wrap it in checkConstraint(value) to check at runtime."
    } else {
        // Disjoint ranges: the value can never be valid, so a runtime check would
        // always fail -- report it as a definite mismatch instead.
        "Value range [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}

/** Reports an error unless [rhs] is provably congruent to [d]'s remainder modulo its divisor. */
private fun verifyDivisibility(rhs: FirExpression, d: Divisibility, context: CheckerContext, reporter: DiagnosticReporter) {
    // Explicit escape hatch: the IR backend injects the validator, so accept without inferring.
    if (isBareEscapeHatch(rhs)) return
    if (d.divisor == 0L) {
        reporter.reportOn(
            rhs.source,
            ConstraintErrors.DIVISIBLE_BY_NOT_VERIFIED,
            "${d.label} divisor must be non-zero.",
            context,
        )
        return
    }
    val expected = d.remainder.mod(d.divisor)
    val residue = inferResidue(rhs, d.divisor, context.session)
    if (residue == expected) return // statically proven -> no runtime check needed

    val label = "${d.label}(${d.divisor}, ${d.remainder})"
    val message = if (residue == null) {
        // Residue can't be determined: some values would be valid, so checkConstraint is right.
        "Cannot prove this satisfies $label: its value modulo ${d.divisor} can't be determined " +
            "statically. Wrap it in checkConstraint(value) to check at runtime."
    } else {
        // A different, known residue: the value can never satisfy the constraint.
        "Value is congruent to $residue modulo ${d.divisor}, which does not match " +
            "$label (remainder $expected): it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.DIVISIBLE_BY_NOT_VERIFIED, message, context)
}

/**
 * Walks [expr] for any integer division whose divisor's range is known to include 0,
 * reporting a divide-by-zero error for each such division. Returns true if any fired.
 */
private fun reportDivisionByZero(expr: FirExpression?, domain: NumericDomain, context: CheckerContext, reporter: DiagnosticReporter): Boolean {
    when (expr) {
        is FirDesugaredAssignmentValueReferenceExpression ->
            return reportDivisionByZero(expr.expressionRef.value, domain, context, reporter)

        is FirFunctionCall -> {
            var found = reportDivisionByZero(expr.dispatchReceiver ?: expr.explicitReceiver, domain, context, reporter)
            for (arg in expr.arguments) {
                if (reportDivisionByZero(arg, domain, context, reporter)) found = true
            }
            val opName = expr.calleeReference.toResolvedNamedFunctionSymbol()?.name?.asString()
            if (opName == "div" || opName == "rem") {
                val divisor = inferInterval(expr.arguments.firstOrNull(), context.session, domain)
                if (!divisor.isUnknown && divisor.min <= 0 && divisor.max >= 0) {
                    val op = if (opName == "rem") "modulo" else "divide"
                    reporter.reportOn(
                        expr.source,
                        ConstraintErrors.INTRANGE_DIVISION_BY_ZERO,
                        "Possible $op by zero: the divisor's range [${divisor.min}, ${divisor.max}] includes 0.",
                        context,
                    )
                    found = true
                }
            }
            return found
        }

        else -> return false
    }
}

/** True if [expr] is a bare 1-arg `checkConstraint(value)` escape hatch. */
private fun isBareEscapeHatch(expr: FirExpression?): Boolean =
    expr is FirFunctionCall &&
        expr.calleeReference.toResolvedNamedFunctionSymbol()?.callableId == CHECK_CONSTRAINT_ID &&
        expr.arguments.size == 1

// ===========================================================================
// Interval inference over the resolved FIR tree
// ===========================================================================

// internal (not private) so the FIR-driven inference can be unit-tested with mocked nodes.
internal fun inferInterval(expr: FirExpression?, session: FirSession, domain: NumericDomain): Interval = when (expr) {
    is FirLiteralExpression ->
        (expr.value as? Number)?.let { Interval.point(it.toLong()) } ?: Interval.UNKNOWN

    is FirFunctionCall -> inferCall(expr, session, domain)

    // A bare variable read: use its declared range (a sound invariant, since every write to it is
    // itself checked). Unannotated variables are unknown.
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.rangeTarget(session)?.interval ?: Interval.UNKNOWN

    // `a++` / `a += ...` desugar so the variable read becomes this reference wrapper.
    is FirDesugaredAssignmentValueReferenceExpression ->
        inferInterval(expr.expressionRef.value, session, domain)

    else -> Interval.UNKNOWN
}

/**
 * Resolves the variable an lValue refers to, unwrapping the desugared reference
 * wrapper that `a++` / `a += ...` use in place of a direct property access.
 */
private fun FirExpression.resolvedVariableSymbolOrNull(): FirVariableSymbol<*>? {
    val access = when (this) {
        is FirQualifiedAccessExpression -> this
        is FirDesugaredAssignmentValueReferenceExpression -> expressionRef.value as? FirQualifiedAccessExpression
        else -> null
    }
    return access?.calleeReference?.toResolvedVariableSymbol()
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

// ===========================================================================
// Residue inference (for @DivisibleBy) -- the value's residue modulo a divisor.
//
// Uses *floored* modulo (Kotlin's `.mod`), i.e. true congruence classes, so residues compose
// soundly through +, -, *: (a OP b) mod n == ((a mod n) OP (b mod n)) mod n. This is why the
// constraint must use floored modulo at runtime too -- `%` would not be sound here.
// ===========================================================================

/**
 * The residue of [expr] modulo [divisor] (floored, in `[0, divisor)`) if it can be determined
 * statically, else null. Tracks literals, congruence-preserving arithmetic, and reads of
 * variables declared `@DivisibleBy` with a divisor that [divisor] divides.
 */
internal fun inferResidue(expr: FirExpression?, divisor: Long, session: FirSession): Long? {
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
        is FirDesugaredAssignmentValueReferenceExpression -> inferResidue(expr.expressionRef.value, divisor, session)

        is FirFunctionCall -> inferResidueCall(expr, divisor, session)

        else -> null
    }
}

private fun inferResidueCall(call: FirFunctionCall, divisor: Long, session: FirSession): Long? {
    val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
    val receiver = inferResidue(call.dispatchReceiver ?: call.explicitReceiver, divisor, session)
    val arg = inferResidue(call.arguments.firstOrNull(), divisor, session)
    return when (callee.name.asString()) {
        "inc" -> residueOp(receiver, 1L, divisor) { a, b -> Math.addExact(a, b) }
        "dec" -> residueOp(receiver, 1L, divisor) { a, b -> Math.subtractExact(a, b) }
        "unaryMinus" -> residueOp(0L, receiver, divisor) { a, b -> Math.subtractExact(a, b) }
        "plus" -> residueOp(receiver, arg, divisor) { a, b -> Math.addExact(a, b) }
        "minus" -> residueOp(receiver, arg, divisor) { a, b -> Math.subtractExact(a, b) }
        "times" -> residueOp(receiver, arg, divisor) { a, b -> Math.multiplyExact(a, b) }
        // div and rem do not preserve congruence; any other call is opaque.
        else -> null
    }
}

/**
 * Combines two known residues with [op], reducing the result modulo [divisor] (floored). Returns
 * null if either residue is unknown, or if [op] overflows Long (possible for a large Long divisor)
 * -- so an unprovable case soundly falls back to `checkConstraint`. internal for unit testing.
 */
internal fun residueOp(a: Long?, b: Long?, divisor: Long, op: (Long, Long) -> Long): Long? {
    if (a == null || b == null) return null
    return try {
        op(a, b).mod(divisor)
    } catch (e: ArithmeticException) {
        null
    }
}

/**
 * Identifies a runtime-only constraint for the transfer proof. The identity is the annotation's
 * class plus all its argument values, so two values share a constraint only when annotated
 * identically -- e.g. `@InverseRange(0, 10)` matches `@InverseRange(0, 10)` but not `@InverseRange(5, 20)`.
 */
private data class ConstraintKey(val annotationClassId: ClassId, val arguments: Map<String, Any>) {
    fun render(): String {
        val args = if (arguments.isEmpty()) "" else
            arguments.entries.joinToString(", ", "(", ")") { (k, v) -> "$k=${renderValue(v)}" }
        return "@${annotationClassId.shortClassName.asString()}$args"
    }

    private fun renderValue(v: Any): String = if (v is ClassId) v.shortClassName.asString() else v.toString()
}

/** The set of runtime-only constraints (by [ConstraintKey]) this variable is declared to satisfy. */
private fun FirVariableSymbol<*>.runtimeConstraintKeys(session: FirSession): Set<ConstraintKey> =
    resolvedAnnotationsWithArguments.mapNotNull { it.runtimeConstraintKey(session) }.toSet()

/**
 * The [ConstraintKey] of this annotation if it is a *runtime-only* `@Constraint` -- i.e. its class
 * is meta-annotated `@Constraint(...)` but isn't one of the [BUILTIN_ANALYZED] constraints (which
 * get interval/residue proofs instead). Otherwise null -- and null too if any argument can't be
 * read as a comparable value, which conservatively forces the runtime `checkConstraint` path.
 */
private fun FirAnnotation.runtimeConstraintKey(session: FirSession): ConstraintKey? {
    val classId = toAnnotationClassId(session) ?: return null
    if (classId in BUILTIN_ANALYZED) return null
    if (!isConstraintAnnotation(session)) return null
    val arguments = comparableArguments() ?: return null
    return ConstraintKey(classId, arguments)
}

/** True if this annotation's class is meta-annotated `@Constraint(...)`. */
private fun FirAnnotation.isConstraintAnnotation(session: FirSession): Boolean {
    val classId = toAnnotationClassId(session) ?: return false
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return false
    return classSymbol.resolvedAnnotationsWithArguments.any { it.toAnnotationClassId(session) == CONSTRAINT_CLASS_ID }
}

/** This annotation's arguments as comparable values (literals by value, `K::class` by class id), or null if any can't be read. */
private fun FirAnnotation.comparableArguments(): Map<String, Any>? {
    val result = mutableMapOf<String, Any>()
    for ((name, expr) in argumentMapping.mapping) {
        result[name.asString()] = comparableValue(expr) ?: return null
    }
    return result
}

internal fun comparableValue(expr: FirExpression): Any? = when (expr) {
    is FirLiteralExpression -> expr.value
    is FirGetClassCall -> (expr.argument as? FirResolvedQualifier)?.classId
    else -> null
}

/** A range constraint plus the numeric domain it lives in (so interval math clamps to the right bounds). */
private class RangeTarget(val interval: Interval, val domain: NumericDomain, val label: String)

/** The range constraint (`@IntRange` or `@LongRange`) on this variable, directly or via an alias. */
private fun FirVariableSymbol<*>.rangeTarget(session: FirSession): RangeTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.rangeTarget(session) }

/** The range constraint on this callable's return type, directly or via an alias. */
private fun FirCallableSymbol<*>.returnTypeRange(session: FirSession): RangeTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.rangeTarget(session) }

/**
 * The range constraint this annotation carries: `@IntRange`/`@LongRange` directly, or -- for an
 * alias such as `@PositiveInt` -- a range meta-annotation on the annotation's own declaration.
 */
private fun FirAnnotation.rangeTarget(session: FirSession): RangeTarget? {
    rangeTargetFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        rangeTargetFor(it.toAnnotationClassId(session), it)
    }
}

private fun rangeTargetFor(classId: ClassId?, range: FirAnnotation): RangeTarget? = when (classId) {
    INT_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.INT, "@IntRange") }
    LONG_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.LONG, "@LongRange") }
    else -> null
}

private fun readInterval(range: FirAnnotation): Interval? {
    val min = range.longArgument("min") ?: return null
    val max = range.longArgument("max") ?: return null
    return Interval(min, max)
}

/** A divisibility constraint: the value must be congruent to [remainder] modulo [divisor]. */
private data class Divisibility(val divisor: Long, val remainder: Long, val label: String)

/** The divisibility constraint (`@DivisibleBy` or `@LongDivisibleBy`) on this variable, directly or via an alias. */
private fun FirVariableSymbol<*>.divisibleBy(session: FirSession): Divisibility? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.divisibilityArgs(session) }

/** The divisibility constraint on this callable's return type, directly or via an alias. */
private fun FirCallableSymbol<*>.returnTypeDivisibleBy(session: FirSession): Divisibility? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.divisibilityArgs(session) }

/**
 * The divisor/remainder this annotation constrains a value to: read off `@DivisibleBy`/`@LongDivisibleBy`
 * directly, or -- for an alias such as `@Even` -- off such a meta-annotation on the annotation's declaration.
 */
private fun FirAnnotation.divisibilityArgs(session: FirSession): Divisibility? {
    divisibilityFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        divisibilityFor(it.toAnnotationClassId(session), it)
    }
}

private fun divisibilityFor(classId: ClassId?, divisibleBy: FirAnnotation): Divisibility? = when (classId) {
    DIVISIBLE_BY_CLASS_ID -> readDivisibility(divisibleBy, "@DivisibleBy")
    LONG_DIVISIBLE_BY_CLASS_ID -> readDivisibility(divisibleBy, "@LongDivisibleBy")
    else -> null
}

private fun readDivisibility(divisibleBy: FirAnnotation, label: String): Divisibility? {
    val divisor = divisibleBy.longArgument("divisor") ?: return null
    // `remainder` defaults to 0 on the annotation; treat an absent argument as that default.
    val remainder = divisibleBy.longArgument("remainder") ?: 0L
    return Divisibility(divisor, remainder, label)
}

private fun FirAnnotation.longArgument(name: String): Long? {
    val expr = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == name }?.value
    // FIR stores integer-literal values as Long, so go through Number.
    return ((expr as? FirLiteralExpression)?.value as? Number)?.toLong()
}
