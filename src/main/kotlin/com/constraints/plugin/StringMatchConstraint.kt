package com.constraints.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
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
        // @Prefix is closed under appending: a value that provably starts with the prefix still does
        // after `+ anything`, so `someFooPrefixed + " tail"` stays valid. This also subsumes @Prefix's
        // plain literal and transfer cases.
        if (target.kind == StringMatchKind.PREFIX && provablyStartsWith(rhs, target.pattern, context.session)) {
            continue
        }
        // @Suffix is the dual -- closed under prepending: `"head " + someBarSuffixed` still ends with it.
        if (target.kind == StringMatchKind.SUFFIX && provablyEndsWith(rhs, target.pattern, context.session)) {
            continue
        }

        val proven: Boolean? = when {
            literal != null -> target.satisfiedBy(literal) // Boolean? -- null only for an invalid @Matches regex
            target in known -> true                         // transfer from an identically-constrained value
            else -> false
        }
        when (proven) {
            true -> {} // statically proven -> no runtime check needed

            null -> reporter.reportOn(
                rhs.source,
                ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                "Cannot evaluate ${target.label}: \"${target.pattern}\" is not a valid regular expression. " +
                    "Wrap the value in checkConstraint(value) to check at runtime.",
                context,
            )

            false -> if (literal != null) reporter.reportOn(
                rhs.source,
                ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                "Value \"$literal\" does not satisfy ${target.label}: it can never be valid.",
                context,
            ) else reporter.reportOn(
                rhs.source,
                ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                "Cannot prove this satisfies ${target.label}: only string literals, prefix/suffix-preserving " +
                    "concatenation, and values already known to satisfy the same constraint are checked " +
                    "statically. Wrap it in checkConstraint(value) to check at runtime.",
                context,
            )
        }
    }
}

/**
 * True if [expr] provably starts with [prefix]: a string literal that does; a `+` concatenation
 * (or string template) whose left/first operand provably does -- since appending on the right can't
 * change the start; or a variable whose declared `@Prefix` is at least as specific (its pattern
 * starts with [prefix]). Anything else is not provable here.
 */
private fun provablyStartsWith(expr: FirExpression?, prefix: String, session: FirSession): Boolean = when (expr) {
    is FirLiteralExpression -> (expr.value as? String)?.startsWith(prefix) ?: false

    // String template "$a b" -- the first piece determines the start.
    is FirStringConcatenationCall -> provablyStartsWith(expr.arguments.firstOrNull(), prefix, session)

    is FirFunctionCall ->
        // `a + b` desugars to `a.plus(b)`; the receiver (a) determines the start.
        if (expr.calleeReference.toResolvedNamedFunctionSymbol()?.name?.asString() == "plus")
            provablyStartsWith(expr.dispatchReceiver ?: expr.explicitReceiver, prefix, session)
        else false

    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.stringMatchTargets(session).orEmpty()
            .any { it.kind == StringMatchKind.PREFIX && it.pattern.startsWith(prefix) }

    is FirDesugaredAssignmentValueReferenceExpression -> provablyStartsWith(expr.expressionRef.value, prefix, session)

    else -> false
}

/**
 * The dual of [provablyStartsWith]: true if [expr] provably ends with [suffix]. A `+` concatenation
 * (or string template) is proven when its right/last operand provably does -- prepending on the left
 * can't change the end -- and a variable when its declared `@Suffix` is at least as specific.
 */
private fun provablyEndsWith(expr: FirExpression?, suffix: String, session: FirSession): Boolean = when (expr) {
    is FirLiteralExpression -> (expr.value as? String)?.endsWith(suffix) ?: false

    // String template "a $b" -- the last piece determines the end.
    is FirStringConcatenationCall -> provablyEndsWith(expr.arguments.lastOrNull(), suffix, session)

    is FirFunctionCall ->
        // `a + b` desugars to `a.plus(b)`; the argument (b) is the right operand and determines the end.
        if (expr.calleeReference.toResolvedNamedFunctionSymbol()?.name?.asString() == "plus")
            provablyEndsWith(expr.arguments.firstOrNull(), suffix, session)
        else false

    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.stringMatchTargets(session).orEmpty()
            .any { it.kind == StringMatchKind.SUFFIX && it.pattern.endsWith(suffix) }

    is FirDesugaredAssignmentValueReferenceExpression -> provablyEndsWith(expr.expressionRef.value, suffix, session)

    else -> false
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
