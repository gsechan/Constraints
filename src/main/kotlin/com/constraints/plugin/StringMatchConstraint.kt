package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// String-matching constraints (@Prefix / @Suffix / @Matches) on CharSequence.
//
// Proven at compile time for STRING LITERALS by evaluating the predicate directly
// (startsWith / endsWith / Regex.matches -- the regex runs only on literals, never
// on arbitrary expressions), and for same-constraint transfers. Everything else needs
// `checkConstraint`. A literal that provably violates the constraint is a hard error.
// ===========================================================================

internal enum class StringMatchKind(val display: String) {
    PREFIX("@Prefix"), SUFFIX("@Suffix"), MATCHES("@Matches"),
}

/** A string-matching constraint: its kind and the literal pattern (prefix / suffix / regex). */
internal data class StringMatch(val kind: StringMatchKind, val pattern: String) {
    val label: String get() = "${kind.display}(\"$pattern\")"

    /**
     * Whether [value] satisfies this constraint. `null` means undecidable -- only happens for a
     * [StringMatchKind.MATCHES] whose pattern is not a valid regex (which would also throw at
     * runtime); we then defer to `checkConstraint` rather than crash the compiler.
     */
    fun satisfiedBy(value: String): Boolean? = when (kind) {
        StringMatchKind.PREFIX -> value.startsWith(pattern)
        StringMatchKind.SUFFIX -> value.endsWith(pattern)
        StringMatchKind.MATCHES -> runCatching { Regex(pattern).matches(value) }.getOrNull()
    }
}

/** All string-matching constraints declared on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.stringMatchTargets(session: FirSession): List<StringMatch> =
    resolvedAnnotationsWithArguments.mapNotNull { it.stringMatch(session) }

/** All string-matching constraints on this callable's return type, directly or via an alias. */
internal fun FirCallableSymbol<*>.returnTypeStringMatches(session: FirSession): List<StringMatch> =
    resolvedReturnType.customAnnotations.mapNotNull { it.stringMatch(session) }

private fun FirAnnotation.stringMatch(session: FirSession): StringMatch? {
    stringMatchFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        stringMatchFor(it.toAnnotationClassId(session), it)
    }
}

private fun stringMatchFor(classId: ClassId?, annotation: FirAnnotation): StringMatch? = when (classId) {
    PREFIX_CLASS_ID -> annotation.stringArgument("prefix")?.let { StringMatch(StringMatchKind.PREFIX, it) }
    SUFFIX_CLASS_ID -> annotation.stringArgument("suffix")?.let { StringMatch(StringMatchKind.SUFFIX, it) }
    MATCHES_CLASS_ID -> annotation.stringArgument("regex")?.let { StringMatch(StringMatchKind.MATCHES, it) }
    else -> null
}

/**
 * Reports an error for each [target] not provably satisfied by [rhs]: a string literal is checked
 * directly (proven, or a hard "can never be valid" error), a same-constraint transfer is accepted,
 * and anything else asks for `checkConstraint`.
 */
internal fun verifyStringMatches(
    rhs: FirExpression,
    targets: List<StringMatch>,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    if (isCheckConstraints(rhs)) return // escape hatch -> the IR injects the validators
    val literal = stringLiteralValue(rhs)
    val known = if (literal == null) knownStringMatches(rhs, context.session) else emptySet()

    for (target in targets) {
        when {
            literal != null -> when (target.satisfiedBy(literal)) {
                true -> {} // statically proven -> no runtime check needed
                false -> reporter.reportOn(
                    rhs.source,
                    ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                    "Value \"$literal\" does not satisfy ${target.label}: it can never be valid.",
                    context,
                )
                null -> reporter.reportOn(
                    rhs.source,
                    ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                    "Cannot evaluate ${target.label}: \"${target.pattern}\" is not a valid regular expression. " +
                        "Wrap the value in checkConstraint(value) to check at runtime.",
                    context,
                )
            }

            target in known -> {} // proven by transfer from a value with the identical constraint

            else -> reporter.reportOn(
                rhs.source,
                ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                "Cannot prove this satisfies ${target.label}: only string literals and values already " +
                    "known to satisfy the same constraint are checked statically. Wrap it in " +
                    "checkConstraint(value) to check at runtime.",
                context,
            )
        }
    }
}

/** The compile-time String value of [expr] if it is a string literal, else null. */
private fun stringLiteralValue(expr: FirExpression?): String? = when (expr) {
    is FirLiteralExpression -> expr.value as? String
    is FirDesugaredAssignmentValueReferenceExpression -> stringLiteralValue(expr.expressionRef.value)
    else -> null
}

/** The string-matching constraints [expr] is already known to satisfy (the variable it reads). */
private fun knownStringMatches(expr: FirExpression?, session: FirSession): Set<StringMatch> = when (expr) {
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.stringMatchTargets(session)?.toSet() ?: emptySet()

    is FirDesugaredAssignmentValueReferenceExpression ->
        knownStringMatches(expr.expressionRef.value, session)

    else -> emptySet()
}
