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
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId

// ===========================================================================
// Custom (opaque-validator) constraints and element constraints.
//
// These can't be analyzed statically -- the validator is an arbitrary predicate -- so the only
// compile-time proof is a *transfer*: assigning from a value already declared with the identical
// constraint (same annotation class and equal argument values, captured by [ConstraintKey]).
// ===========================================================================

/**
 * Identifies a runtime-only constraint for the transfer proof. The identity is the annotation's
 * class plus all its argument values, so two values share a constraint only when annotated
 * identically -- e.g. `@InverseRange(0, 10)` matches `@InverseRange(0, 10)` but not `@InverseRange(5, 20)`.
 */
internal data class ConstraintKey(val annotationClassId: ClassId, val arguments: Map<String, Any>) {
    fun render(): String {
        val args = if (arguments.isEmpty()) "" else
            arguments.entries.joinToString(", ", "(", ")") { (k, v) -> "$k=${renderValue(v)}" }
        return "@${annotationClassId.shortClassName.asString()}$args"
    }

    private fun renderValue(v: Any): String = if (v is ClassId) v.shortClassName.asString() else v.toString()
}

// --- Value-level custom constraints (@Constraint) ---

/** The set of runtime-only constraints (by [ConstraintKey]) this variable is declared to satisfy. */
internal fun FirVariableSymbol<*>.runtimeConstraintKeys(session: FirSession): Set<ConstraintKey> =
    resolvedAnnotationsWithArguments.mapNotNull { it.runtimeConstraintKey(session) }.toSet()

/** The set of runtime-only constraints (by [ConstraintKey]) on this callable's return type. */
internal fun FirCallableSymbol<*>.returnTypeConstraintKeys(session: FirSession): Set<ConstraintKey> =
    resolvedReturnType.customAnnotations.mapNotNull { it.runtimeConstraintKey(session) }.toSet()

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

// --- Element-level constraints (@ElementConstraint) ---

/** The set of element constraints (by [ConstraintKey]) this variable is declared to satisfy on its elements. */
internal fun FirVariableSymbol<*>.elementConstraintKeys(session: FirSession): Set<ConstraintKey> =
    resolvedAnnotationsWithArguments.mapNotNull { it.elementConstraintKey(session) }.toSet()

/** The set of element constraints (by [ConstraintKey]) on this callable's return-type elements. */
internal fun FirCallableSymbol<*>.returnTypeElementConstraintKeys(session: FirSession): Set<ConstraintKey> =
    resolvedReturnType.customAnnotations.mapNotNull { it.elementConstraintKey(session) }.toSet()

/**
 * The [ConstraintKey] of this annotation if it is an element constraint -- i.e. its class is
 * meta-annotated `@ElementConstraint(...)`. Null if any argument can't be read as a comparable
 * value, conservatively requiring `checkConstraint`.
 */
private fun FirAnnotation.elementConstraintKey(session: FirSession): ConstraintKey? {
    val classId = toAnnotationClassId(session) ?: return null
    if (!isElementConstraintAnnotation(session)) return null
    val arguments = comparableArguments() ?: return null
    return ConstraintKey(classId, arguments)
}

/** True if this annotation's class is meta-annotated `@ElementConstraint(...)`. */
private fun FirAnnotation.isElementConstraintAnnotation(session: FirSession): Boolean {
    val classId = toAnnotationClassId(session) ?: return false
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return false
    return classSymbol.resolvedAnnotationsWithArguments.any { it.toAnnotationClassId(session) == ELEMENT_CONSTRAINT_CLASS_ID }
}

// --- Shared argument reading ---

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

// --- Verification (transfer proof) ---

/**
 * Reports [ConstraintErrors.CONSTRAINT_NOT_VALIDATED] unless every runtime-only constraint in
 * [required] is satisfied by [rhs] -- i.e. [rhs] is a bare `checkConstraint(...)` (deferred to
 * runtime) or is already known to satisfy each: a transfer from a value carrying the identical
 * constraint (same validator, same annotation arguments). Returns true if an error was reported, so
 * a caller can stop instead of piling on a further range/divisibility error for the same expression.
 */
internal fun verifyRuntimeConstraints(
    required: Set<ConstraintKey>,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
): Boolean {
    if (required.isEmpty() || isCheckConstraints(rhs)) return false
    val missing = required - knownConstraints(rhs, context.session)
    if (missing.isEmpty()) return false
    val names = missing.joinToString(", ") { it.render() }
    reporter.reportOn(
        rhs.source,
        ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
        "Cannot prove this satisfies $names: an opaque validator can't be checked statically. " +
            "Wrap it in checkConstraint(value) to validate at runtime, or use a value already known to " +
            "satisfy the same constraint (same annotation and arguments).",
        context,
    )
    return true
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

/**
 * Reports [CONSTRAINT_NOT_VALIDATED] unless every `@ElementConstraint` key in [required] is
 * satisfied by [rhs] -- i.e. the RHS is a variable already declared with the same element
 * constraint (transfer proof). Returns true if an error was reported.
 */
internal fun verifyElementConstraints(
    required: Set<ConstraintKey>,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
): Boolean {
    if (required.isEmpty() || isCheckConstraints(rhs)) return false
    val missing = required - knownElementConstraints(rhs, context.session)
    if (missing.isEmpty()) return false
    val names = missing.joinToString(", ") { it.render() }
    reporter.reportOn(
        rhs.source,
        ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
        "Cannot prove all elements of this collection satisfy $names: element constraints cannot be " +
            "checked statically. Wrap it in checkConstraint(value) to validate at runtime, or assign " +
            "from a collection already known to have all elements satisfying the same constraint.",
        context,
    )
    return true
}

private fun knownElementConstraints(expr: FirExpression?, session: FirSession): Set<ConstraintKey> = when (expr) {
    is FirPropertyAccessExpression ->
        expr.calleeReference.toResolvedVariableSymbol()?.elementConstraintKeys(session) ?: emptySet()

    is FirDesugaredAssignmentValueReferenceExpression ->
        knownElementConstraints(expr.expressionRef.value, session)

    else -> emptySet()
}

// --- Element-TYPE constraints: annotations on a collection's element type argument ---
//
// `List<@IntRange(0, 10) Int>` -- the `@IntRange(0, 10)` is on the element type, and means "every
// element satisfies it". Treated like an @ElementConstraint, but sourced from the type argument and
// reusing the element's own validator (so built-in constraints count too -- per element they run
// their normal validator). Proven only by checkConstraint (the IR injects per-element validation)
// or transfer from a value whose element type carries the same constraints.

private fun FirAnnotation.elementTypeConstraintKey(session: FirSession): ConstraintKey? {
    val classId = toAnnotationClassId(session) ?: return null
    if (!isConstraintAnnotation(session)) return null
    val arguments = comparableArguments() ?: return null
    return ConstraintKey(classId, arguments)
}

/** Whether this type carries any element-type constraint, at any nesting level. */
internal fun ConeKotlinType.hasElementConstraints(session: FirSession): Boolean {
    val elem = typeArguments.firstOrNull()?.type ?: return false
    return elem.customAnnotations.any { it.isConstraintAnnotation(session) } || elem.hasElementConstraints(session)
}

/** Every element-type constraint key at every nesting level (for diagnostics). */
private fun ConeKotlinType.allElementKeys(session: FirSession): Set<ConstraintKey> {
    val elem = typeArguments.firstOrNull()?.type ?: return emptySet()
    return elem.customAnnotations.mapNotNull { it.elementTypeConstraintKey(session) }.toSet() + elem.allElementKeys(session)
}

/**
 * Reports an error unless every element-type constraint of [collectionType] -- at every nesting
 * level -- is satisfied by [rhs]: a `checkConstraint` (per-element validators injected); a transfer
 * from a value whose nested element constraints imply these; or a builder (`listOf`/`setOf`/…)
 * whose every element provably satisfies them, recursing through nested builders. Returns true if
 * an error was reported.
 */
internal fun verifyElementTypeConstraints(
    collectionType: ConeKotlinType,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
): Boolean {
    val session = context.session
    if (!collectionType.hasElementConstraints(session) || isCheckConstraints(rhs)) return false

    // Transfer: rhs reads a value whose (nested) element constraints imply these.
    knownCollectionType(rhs, session)?.let { if (collectionType.elementConstraintsImpliedBy(it, session)) return false }

    // Builder literal: recurse through the (possibly nested) builders.
    builderElements(rhs)?.let {
        return when (verifyElements(it, collectionType, context, reporter)) {
            ElementVerdict.PROVEN -> false
            ElementVerdict.VIOLATED -> true
            ElementVerdict.UNKNOWN -> {
                reporter.reportOn(rhs.source, ConstraintErrors.CONSTRAINT_NOT_VALIDATED, needsCheckConstraintMessage(collectionType, session), context)
                true
            }
        }
    }

    // Not a builder, not a transfer -> defer to runtime.
    reporter.reportOn(rhs.source, ConstraintErrors.CONSTRAINT_NOT_VALIDATED, needsCheckConstraintMessage(collectionType, session), context)
    return true
}

/** lhs's element constraints at every nesting level are implied by [other]'s (a sound transfer). */
private fun ConeKotlinType.elementConstraintsImpliedBy(other: ConeKotlinType, session: FirSession): Boolean {
    val lhsElem = typeArguments.firstOrNull()?.type ?: return true
    val rhsElem = other.typeArguments.firstOrNull()?.type ?: return false
    val lhsKeys = lhsElem.customAnnotations.mapNotNull { it.elementTypeConstraintKey(session) }.toSet()
    val rhsKeys = rhsElem.customAnnotations.mapNotNull { it.elementTypeConstraintKey(session) }.toSet()
    if (!rhsKeys.containsAll(lhsKeys)) return false
    return lhsElem.elementConstraintsImpliedBy(rhsElem, session)
}

private fun knownCollectionType(expr: FirExpression?, session: FirSession): ConeKotlinType? = when (expr) {
    is FirPropertyAccessExpression -> expr.calleeReference.toResolvedVariableSymbol()?.resolvedReturnType
    is FirDesugaredAssignmentValueReferenceExpression -> knownCollectionType(expr.expressionRef.value, session)
    else -> null
}

// --- Builder-literal element proof (listOf / setOf / … with known elements) ---

private val ELEMENT_BUILDERS = setOf(
    "kotlin.collections.listOf", "kotlin.collections.mutableListOf", "kotlin.collections.arrayListOf",
    "kotlin.collections.setOf", "kotlin.collections.mutableSetOf",
    "kotlin.collections.hashSetOf", "kotlin.collections.linkedSetOf", "kotlin.collections.sortedSetOf",
)
private val EMPTY_BUILDERS = setOf("kotlin.collections.emptyList", "kotlin.collections.emptySet")

/** The element expressions of a known list/set builder call, or null if it isn't one (or uses a spread). */
private fun builderElements(rhs: FirExpression?): List<FirExpression>? {
    val call = rhs as? FirFunctionCall ?: return null
    val id = call.calleeReference.toResolvedNamedFunctionSymbol()?.callableId ?: return null
    val fqName = "${id.packageName.asString()}.${id.callableName.asString()}"
    if (fqName in EMPTY_BUILDERS) return emptyList()
    if (fqName !in ELEMENT_BUILDERS) return null
    if (call.arguments.isEmpty()) return emptyList() // listOf() with no args
    // Multiple vararg elements arrive wrapped in a single FirVarargArgumentsExpression, but a single
    // element comes through unwrapped (as the bare argument expression) -- handle both.
    val elements = (call.arguments.singleOrNull() as? FirVarargArgumentsExpression)?.arguments ?: call.arguments
    if (elements.any { it is FirSpreadArgumentExpression }) return null // can't enumerate *spread
    return elements
}

internal enum class ElementVerdict { PROVEN, VIOLATED, UNKNOWN }

/**
 * Recursively classifies a builder's [elements] against [collectionType]'s element type: each element
 * against the element type's *direct* constraints (via [verdict]), and -- when the element type is
 * itself a constrained collection -- the element's own nested builder. Reports violations at the
 * offending element; returns the aggregate verdict over the whole subtree.
 */
private fun verifyElements(
    elements: List<FirExpression>,
    collectionType: ConeKotlinType,
    context: CheckerContext,
    reporter: DiagnosticReporter,
): ElementVerdict {
    val session = context.session
    val elementType = collectionType.typeArguments.firstOrNull()?.type ?: return ElementVerdict.PROVEN
    val directAnns = elementType.customAnnotations.filter { it.isConstraintAnnotation(session) }
    val nested = elementType.hasElementConstraints(session)
    var agg = ElementVerdict.PROVEN
    for (element in elements) {
        for (ann in directAnns) {
            when (verdict(element, ann, session)) {
                ElementVerdict.PROVEN -> {}
                ElementVerdict.UNKNOWN -> agg = worse(agg, ElementVerdict.UNKNOWN)
                ElementVerdict.VIOLATED -> {
                    val label = ann.elementTypeConstraintKey(session)?.render() ?: "the element constraint"
                    reporter.reportOn(
                        element.source,
                        ConstraintErrors.CONSTRAINT_NOT_VALIDATED,
                        "This element does not satisfy $label: it can never be valid.",
                        context,
                    )
                    agg = ElementVerdict.VIOLATED
                }
            }
        }
        if (nested) {
            val inner = builderElements(element)
            agg = worse(agg, if (inner != null) verifyElements(inner, elementType, context, reporter) else ElementVerdict.UNKNOWN)
        }
    }
    return agg
}

/** Combines two verdicts: VIOLATED dominates UNKNOWN dominates PROVEN. */
private fun worse(a: ElementVerdict, b: ElementVerdict): ElementVerdict = when {
    a == ElementVerdict.VIOLATED || b == ElementVerdict.VIOLATED -> ElementVerdict.VIOLATED
    a == ElementVerdict.UNKNOWN || b == ElementVerdict.UNKNOWN -> ElementVerdict.UNKNOWN
    else -> ElementVerdict.PROVEN
}

private fun needsCheckConstraintMessage(collectionType: ConeKotlinType, session: FirSession): String {
    val names = collectionType.allElementKeys(session).joinToString(", ") { it.render() }
    return "Cannot prove every element satisfies $names: wrap it in checkConstraint(value), assign from a " +
        "value whose element type carries the same constraints, or use a builder (listOf/setOf) whose " +
        "elements each provably satisfy it."
}

/**
 * Three-valued verdict of [element] against [annotation], reusing the scalar checkers' own inference:
 * PROVEN (statically satisfies), VIOLATED (can never satisfy -- e.g. a range disjoint from the value),
 * or UNKNOWN (undecidable, or an opaque custom constraint).
 */
private fun verdict(element: FirExpression, annotation: FirAnnotation, session: FirSession): ElementVerdict {
    annotation.rangeTarget(session)?.let {
        val inferred = inferInterval(element, session, it.domain)
        return when {
            inferred.subsetOf(it.interval) -> ElementVerdict.PROVEN
            inferred.overlaps(it.interval) -> ElementVerdict.UNKNOWN
            else -> ElementVerdict.VIOLATED
        }
    }
    annotation.doubleRangeTarget(session)?.let {
        val inferred = inferDoubleInterval(element, session)
        return when {
            inferred.subsetOf(it.interval) -> ElementVerdict.PROVEN
            inferred.isUnknown || inferred.overlaps(it.interval) -> ElementVerdict.UNKNOWN
            else -> ElementVerdict.VIOLATED
        }
    }
    annotation.divisibilityArgs(session)?.let { d ->
        if (d.divisor == 0L) return ElementVerdict.UNKNOWN
        return when (inferRemainder(element, d.divisor, session)) {
            d.remainder.mod(d.divisor) -> ElementVerdict.PROVEN
            null -> ElementVerdict.UNKNOWN
            else -> ElementVerdict.VIOLATED
        }
    }
    annotation.stringLengthTarget(session)?.let {
        val inferred = inferStringLength(element, session)
        return when {
            inferred.subsetOf(it.interval) -> ElementVerdict.PROVEN
            inferred.isUnknown || inferred.overlaps(it.interval) -> ElementVerdict.UNKNOWN
            else -> ElementVerdict.VIOLATED
        }
    }
    annotation.collectionSizeTarget(session)?.let {
        val inferred = inferCollectionSize(element, session)
        return when {
            inferred.subsetOf(it.interval) -> ElementVerdict.PROVEN
            inferred.isUnknown || inferred.overlaps(it.interval) -> ElementVerdict.UNKNOWN
            else -> ElementVerdict.VIOLATED
        }
    }
    annotation.stringMatch(session)?.let { return stringMatchElementVerdict(element, it, session) }
    return ElementVerdict.UNKNOWN // opaque custom constraint: can't decide per element
}
