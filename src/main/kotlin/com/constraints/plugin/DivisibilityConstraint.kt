package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// Divisibility constraints (@DivisibleBy / @LongDivisibleBy / @ShortDivisibleBy /
// @ByteDivisibleBy). Reasoning is over residues (floored modulo); the FIR-tree
// walk lives in RemainderInference.kt.
// ===========================================================================

/** A divisibility constraint: the value must be congruent to [remainder] modulo [divisor]. */
internal data class Divisibility(val divisor: Long, val remainder: Long, val label: String)

/** The divisibility constraint (`@DivisibleBy` or `@LongDivisibleBy`) on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.divisibleBy(session: FirSession): Divisibility? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.divisibilityArgs(session) }

/** The divisibility constraint on this callable's return type, directly or via an alias. */
internal fun FirCallableSymbol<*>.returnTypeDivisibleBy(session: FirSession): Divisibility? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.divisibilityArgs(session) }

/**
 * The divisor/remainder this annotation constrains a value to: read off `@DivisibleBy`/`@LongDivisibleBy`
 * directly, or -- for an alias such as `@Even` -- off such a meta-annotation on the annotation's declaration.
 */
internal fun FirAnnotation.divisibilityArgs(session: FirSession): Divisibility? {
    divisibilityFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        divisibilityFor(it.toAnnotationClassId(session), it)
    }
}

private fun divisibilityFor(classId: ClassId?, divisibleBy: FirAnnotation): Divisibility? = when (classId) {
    BYTE_DIVISIBLE_BY_CLASS_ID -> readDivisibility(divisibleBy, "@ByteDivisibleBy")
    SHORT_DIVISIBLE_BY_CLASS_ID -> readDivisibility(divisibleBy, "@ShortDivisibleBy")
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

/** Reports an error unless [rhs] is provably congruent to [d]'s remainder modulo its divisor. */
internal fun verifyDivisibility(rhs: FirExpression, d: Divisibility, context: CheckerContext, reporter: DiagnosticReporter) {
    // Explicit escape hatch: the IR backend injects the validator, so accept without inferring.
    if (isCheckConstraints(rhs)) return
    // A zero divisor is reported at the annotation by DivisibleByDivisorChecker; bail before the
    // residue math (mod 0 would throw and crash the checker).
    if (d.divisor == 0L) return
    val expected = d.remainder.mod(d.divisor)
    val residue = inferRemainder(rhs, d.divisor, context.session)
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
 * A `@DivisibleBy` / `@LongDivisibleBy` with a zero divisor is meaningless (`mod 0`). This rejects it
 * at every site the annotation is written -- value, return type, parameter, or an alias definition --
 * so it's a compile error rather than a runtime throw, regardless of how the value is assigned.
 */
object DivisibleByDivisorChecker : FirAnnotationChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirAnnotation) {
        val label = when (expression.toAnnotationClassId(context.session)) {
            BYTE_DIVISIBLE_BY_CLASS_ID -> "@ByteDivisibleBy"
            SHORT_DIVISIBLE_BY_CLASS_ID -> "@ShortDivisibleBy"
            DIVISIBLE_BY_CLASS_ID -> "@DivisibleBy"
            LONG_DIVISIBLE_BY_CLASS_ID -> "@LongDivisibleBy"
            else -> return
        }
        if (expression.longArgument("divisor") == 0L) {
            reporter.reportOn(
                expression.source,
                ConstraintErrors.DIVISIBLE_BY_NOT_VERIFIED,
                "$label divisor must be non-zero.",
                context,
            )
        }
    }
}
