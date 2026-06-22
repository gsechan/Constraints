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
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
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

private val BYTE_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ByteRange"))
private val SHORT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ShortRange"))
private val INT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("IntRange"))
private val LONG_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("LongRange"))
private val FLOAT_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("FloatRange"))
private val DOUBLE_RANGE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("DoubleRange"))
private val BYTE_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ByteDivisibleBy"))
private val SHORT_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("ShortDivisibleBy"))
private val DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("DivisibleBy"))
private val LONG_DIVISIBLE_BY_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("LongDivisibleBy"))
private val STRING_LENGTH_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("StringLength"))
private val COLLECTION_SIZE_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("CollectionSize"))
private val CONSTRAINT_CLASS_ID = ClassId(FqName("com.constraints"), Name.identifier("Constraint"))
private val CHECK_CONSTRAINT_ID = CallableId(FqName("com.constraints"), Name.identifier("checkConstraint"))

/**
 * Constraints the checker analyzes statically (interval for ranges, residue for divisibility).
 * They are detected by their own class id, so they are excluded from the generic transfer-only
 * fallback that applies to every other `@Constraint`.
 */
private val BUILTIN_ANALYZED = setOf(
    BYTE_RANGE_CLASS_ID, SHORT_RANGE_CLASS_ID, INT_RANGE_CLASS_ID, LONG_RANGE_CLASS_ID,
    FLOAT_RANGE_CLASS_ID, DOUBLE_RANGE_CLASS_ID,
    BYTE_DIVISIBLE_BY_CLASS_ID, SHORT_DIVISIBLE_BY_CLASS_ID, DIVISIBLE_BY_CLASS_ID, LONG_DIVISIBLE_BY_CLASS_ID,
    STRING_LENGTH_CLASS_ID, COLLECTION_SIZE_CLASS_ID,
)

// The interval lattice ([Interval]) and its arithmetic ([NumericDomain]) -- the pure-Kotlin range
// reasoning -- live in their own files; this file is only the FIR/compiler interaction.

// ===========================================================================
// FIR wiring
// ===========================================================================

class ConstraintFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::ConstraintCheckersExtension
        // Public registration path for the plugin's diagnostics (replaces the
        // internal renderer-map / RootDiagnosticRendererFactory approach).
        registerDiagnosticContainers(ConstraintErrors)
    }
}

class ConstraintCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers = object : DeclarationCheckers() {
        override val propertyCheckers = setOf(ConstraintPropertyChecker)
        override val regularClassCheckers = setOf(ConstraintValidatorChecker)
    }

    override val expressionCheckers = object : ExpressionCheckers() {
        override val variableAssignmentCheckers = setOf(ConstraintAssignmentChecker)
        override val returnExpressionCheckers = setOf(ConstraintReturnChecker)
        override val annotationCheckers = setOf(DivisibleByDivisorChecker)
    }
}

/** Initialisation:  `@IntRange(min,max) var x = <initializer>` */
object ConstraintPropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        val initializer = declaration.initializer ?: return
        verifyConstraints(declaration.symbol, initializer, context, reporter)
    }
}

/** Reassignment, including `a++` / `a--` (which desugar to `a = a.inc()` / `a.dec()`). */
object ConstraintAssignmentChecker : FirVariableAssignmentChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        val symbol = expression.lValue.resolvedVariableSymbolOrNull() ?: return
        verifyConstraints(symbol, expression.rValue, context, reporter)
    }
}

/** Return:  every `return <expr>` from a function with a constrained return type must honor it. */
object ConstraintReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val function = expression.target.labeledElement as? FirFunction ?: return
        val result = expression.result
        // Runtime-only constraints on the return type; if unsatisfied, stop (as in verifyConstraints).
        if (verifyRuntimeConstraints(function.symbol.returnTypeConstraintKeys(context.session), result, context, reporter)) return
        function.symbol.returnTypeRange(context.session)?.let { verifyRange(result, it, context, reporter) }
        function.symbol.returnTypeDoubleRange(context.session)?.let { verifyDoubleRange(result, it, context, reporter) }
        function.symbol.returnTypeStringLength(context.session)?.let { verifyStringLength(result, it, context, reporter) }
        function.symbol.returnTypeCollectionSize(context.session)?.let { verifyCollectionSize(result, it, context, reporter) }
        function.symbol.returnTypeDivisibleBy(context.session)?.let { verifyDivisibility(result, it, context, reporter) }
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
 *  - `@IntRange` / `@LongRange` / `@ShortRange` / `@ByteRange`: proven by interval inference ([verifyRange]).
 *  - `@FloatRange` / `@DoubleRange`: proven by double-interval inference ([verifyDoubleRange]).
 *  - `@StringLength`: proven by string-length inference ([verifyStringLength]).
 *  - `@CollectionSize`: proven by collection-size inference ([verifyCollectionSize]).
 *  - `@DivisibleBy` / `@LongDivisibleBy` etc.: proven by residue inference ([verifyDivisibility]).
 */
private fun verifyConstraints(
    symbol: FirVariableSymbol<*>,
    rhs: FirExpression,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    val required = symbol.runtimeConstraintKeys(context.session)
    val range = symbol.rangeTarget(context.session)
    val doubleRange = symbol.doubleRangeTarget(context.session)
    val stringLength = symbol.stringLengthTarget(context.session)
    val collectionSize = symbol.collectionSizeTarget(context.session)
    val divisibility = symbol.divisibleBy(context.session)
    if (required.isEmpty() && range == null && doubleRange == null &&
        stringLength == null && collectionSize == null && divisibility == null) return

    // The escape hatch satisfies every constraint -- the IR backend injects the checks.
    if (isCheckConstraints(rhs)) return

    // Runtime-only constraints first; if one is unsatisfied, stop (don't pile on other errors).
    if (verifyRuntimeConstraints(required, rhs, context, reporter)) return

    if (range != null) verifyRange(rhs, range, context, reporter)
    if (doubleRange != null) verifyDoubleRange(rhs, doubleRange, context, reporter)
    if (stringLength != null) verifyStringLength(rhs, stringLength, context, reporter)
    if (collectionSize != null) verifyCollectionSize(rhs, collectionSize, context, reporter)
    if (divisibility != null) verifyDivisibility(rhs, divisibility, context, reporter)
}

/**
 * Reports [ConstraintErrors.CONSTRAINT_NOT_VALIDATED] unless every runtime-only constraint in
 * [required] is satisfied by [rhs] -- i.e. [rhs] is a bare `checkConstraint(...)` (deferred to
 * runtime) or is already known to satisfy each: a transfer from a value carrying the identical
 * constraint (same validator, same annotation arguments). Returns true if an error was reported, so
 * a caller can stop instead of piling on a further range/divisibility error for the same expression.
 */
private fun verifyRuntimeConstraints(
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

/** Reports an error unless [rhs]'s inferred interval is provably within [target]. */
private fun verifyRange(rhs: FirExpression, target: RangeTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    // A possible divide-by-zero anywhere in the expression is a hard error in its
    // own right -- report it and stop (the range check would just add noise).
    if (reportDivisionByZero(rhs, target.domain, context, reporter)) return
    // Single-arg `checkConstraint(value)`: explicit escape hatch; the IR backend fills its bounds
    // from `target`, so accept it here without inferring a range.
    if (isCheckConstraints(rhs)) return
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

/**
 * Reports an error unless [rhs] is provably within [target]'s floating point bounds. Literals,
 * same-range transfers, and arithmetic (`+ - * / inc dec unaryMinus`) are proven where possible.
 * If arithmetic overflows to Infinity the result widens to UNKNOWN and `checkConstraint` is
 * required. Minor rounding at boundaries is accepted as a known trade-off.
 */
private fun verifyDoubleRange(rhs: FirExpression, target: DoubleRangeTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferDoubleInterval(rhs, context.session)
    if (inferred.subsetOf(bounds)) return // statically proven in range -> no runtime check needed

    val label = "${target.label}(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its range cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "Value range [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}

/** Reports an error unless the string length of [rhs] is provably within [target]'s bounds. */
private fun verifyStringLength(rhs: FirExpression, target: LengthTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferStringLength(rhs, context.session)
    if (inferred.subsetOf(bounds)) return
    val label = "@StringLength(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its length cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "String length [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the length ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}

/** Reports an error unless the collection size of [rhs] is provably within [target]'s bounds. */
private fun verifyCollectionSize(rhs: FirExpression, target: LengthTarget, context: CheckerContext, reporter: DiagnosticReporter) {
    if (isCheckConstraints(rhs)) return
    val bounds = target.interval
    val inferred = inferCollectionSize(rhs, context.session)
    if (inferred.subsetOf(bounds)) return
    val label = "@CollectionSize(${bounds.min}, ${bounds.max})"
    val message = if (inferred.isUnknown || inferred.overlaps(bounds)) {
        "Cannot prove this satisfies $label: its size cannot be determined statically. " +
            "Wrap it in checkConstraint(value) to check at runtime."
    } else {
        "Collection size [${inferred.min}, ${inferred.max}] does not match $label: " +
            "the size ranges do not overlap, so it can never be valid."
    }
    reporter.reportOn(rhs.source, ConstraintErrors.INTRANGE_NOT_VERIFIED, message, context)
}

/** Reports an error unless [rhs] is provably congruent to [d]'s remainder modulo its divisor. */
private fun verifyDivisibility(rhs: FirExpression, d: Divisibility, context: CheckerContext, reporter: DiagnosticReporter) {
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
private fun isCheckConstraints(expr: FirExpression?): Boolean =
    expr is FirFunctionCall &&
        expr.calleeReference.toResolvedNamedFunctionSymbol()?.callableId == CHECK_CONSTRAINT_ID &&
        expr.arguments.size == 1

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

/** The set of runtime-only constraints (by [ConstraintKey]) on this callable's return type. */
private fun FirCallableSymbol<*>.returnTypeConstraintKeys(session: FirSession): Set<ConstraintKey> =
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
internal class RangeTarget(val interval: Interval, val domain: NumericDomain, val label: String)

/** The range constraint (`@IntRange` or `@LongRange`) on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.rangeTarget(session: FirSession): RangeTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.rangeTarget(session) }

/** The range constraint on this callable's return type, directly or via an alias. */
internal fun FirCallableSymbol<*>.returnTypeRange(session: FirSession): RangeTarget? =
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
    BYTE_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.BYTE, "@ByteRange") }
    SHORT_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.SHORT, "@ShortRange") }
    INT_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.INT, "@IntRange") }
    LONG_RANGE_CLASS_ID -> readInterval(range)?.let { RangeTarget(it, NumericDomain.LONG, "@LongRange") }
    else -> null
}

private fun readInterval(range: FirAnnotation): Interval? {
    val min = range.longArgument("min") ?: return null
    val max = range.longArgument("max") ?: return null
    return Interval(min, max)
}

// ---------------------------------------------------------------------------
// Floating-point range targets (@FloatRange / @DoubleRange)
//
// Uses DoubleInterval rather than Interval (Long-based) -- Float/Double bounds can't be
// represented losslessly in Long. Arithmetic is NOT tracked (see DoubleInterval).
// ---------------------------------------------------------------------------

/** A Float/Double range constraint: its bounds and a display label. */
internal class DoubleRangeTarget(val interval: DoubleInterval, val label: String)

/** The floating-point range constraint on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.doubleRangeTarget(session: FirSession): DoubleRangeTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.doubleRangeTarget(session) }

/** The floating-point range constraint on this callable's return type, directly or via an alias. */
internal fun FirCallableSymbol<*>.returnTypeDoubleRange(session: FirSession): DoubleRangeTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.doubleRangeTarget(session) }

/** The DoubleRangeTarget this annotation carries (directly or via alias), or null. */
internal fun FirAnnotation.doubleRangeTarget(session: FirSession): DoubleRangeTarget? {
    doubleRangeTargetFor(toAnnotationClassId(session), this)?.let { return it }
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        doubleRangeTargetFor(it.toAnnotationClassId(session), it)
    }
}

private fun doubleRangeTargetFor(classId: ClassId?, range: FirAnnotation): DoubleRangeTarget? = when (classId) {
    FLOAT_RANGE_CLASS_ID -> readDoubleInterval(range)?.let { DoubleRangeTarget(it, "@FloatRange") }
    DOUBLE_RANGE_CLASS_ID -> readDoubleInterval(range)?.let { DoubleRangeTarget(it, "@DoubleRange") }
    else -> null
}

private fun readDoubleInterval(range: FirAnnotation): DoubleInterval? {
    val min = range.doubleArgument("min") ?: return null
    val max = range.doubleArgument("max") ?: return null
    return DoubleInterval(min, max)
}

// inferDoubleInterval lives in FloatInference.kt; inferStringLength / inferCollectionSize live in their own files.

private fun FirAnnotation.doubleArgument(name: String): Double? {
    val expr = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == name }?.value
    return ((expr as? FirLiteralExpression)?.value as? Number)?.toDouble()
}

// ---------------------------------------------------------------------------
// Length targets (@StringLength and @CollectionSize)
//
// Both use the same LengthTarget struct (an Interval on a count). Inference lives
// in StringLengthInference.kt and CollectionSizeInference.kt respectively.
// ---------------------------------------------------------------------------

/** A length/size constraint: its min/max bounds expressed as an [Interval]. */
internal class LengthTarget(val interval: Interval)

// --- @StringLength ---

internal fun FirVariableSymbol<*>.stringLengthTarget(session: FirSession): LengthTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.stringLengthTarget(session) }

internal fun FirCallableSymbol<*>.returnTypeStringLength(session: FirSession): LengthTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.stringLengthTarget(session) }

internal fun FirAnnotation.stringLengthTarget(session: FirSession): LengthTarget? {
    if (toAnnotationClassId(session) == STRING_LENGTH_CLASS_ID) return readLengthTarget(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        if (it.toAnnotationClassId(session) == STRING_LENGTH_CLASS_ID) readLengthTarget(it) else null
    }
}

// --- @CollectionSize ---

internal fun FirVariableSymbol<*>.collectionSizeTarget(session: FirSession): LengthTarget? =
    resolvedAnnotationsWithArguments.firstNotNullOfOrNull { it.collectionSizeTarget(session) }

internal fun FirCallableSymbol<*>.returnTypeCollectionSize(session: FirSession): LengthTarget? =
    resolvedReturnType.customAnnotations.firstNotNullOfOrNull { it.collectionSizeTarget(session) }

internal fun FirAnnotation.collectionSizeTarget(session: FirSession): LengthTarget? {
    if (toAnnotationClassId(session) == COLLECTION_SIZE_CLASS_ID) return readLengthTarget(this)
    val classId = toAnnotationClassId(session) ?: return null
    val classSymbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return classSymbol.resolvedAnnotationsWithArguments.firstNotNullOfOrNull {
        if (it.toAnnotationClassId(session) == COLLECTION_SIZE_CLASS_ID) readLengthTarget(it) else null
    }
}

private fun readLengthTarget(annotation: FirAnnotation): LengthTarget? {
    val min = annotation.longArgument("min") ?: return null
    val max = annotation.longArgument("max") ?: return null
    return LengthTarget(Interval(min, max))
}

/** A divisibility constraint: the value must be congruent to [remainder] modulo [divisor]. */
internal data class Divisibility(val divisor: Long, val remainder: Long, val label: String)

/** The divisibility constraint (`@DivisibleBy` or `@LongDivisibleBy`) on this variable, directly or via an alias. */
internal fun FirVariableSymbol<*>.divisibleBy(session: FirSession): Divisibility? =
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

private fun FirAnnotation.longArgument(name: String): Long? {
    val expr = argumentMapping.mapping.entries.firstOrNull { it.key.asString() == name }?.value
    // FIR stores integer-literal values as Long, so go through Number.
    return ((expr as? FirLiteralExpression)?.value as? Number)?.toLong()
}
