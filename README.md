# Constraints

The constraints library is a compiler plugin that brings refinement types to kotlin.  What it allows you to do is to specify a subset of values of a type that are allowed to be stored in a value, parameter, or returned by a function.  This makes it literally impossible to pass a bad value to a function, and in many cases actually becomes a compile time error to do so. This isn't a new idea, many functional languages have it.  But surprisingly enough, so does Kotlin to a small degree.  You can really consider String to be a constrained version of String?  where the constraint is that the value is not null.  Explicit nullability has solved a lot of bugs, and this library attempts to generalize that to more usecases.

## Advantages of constraints

### Explicitness
Constraints have several advantages in programming.  First, they make the contracts of your function explicit.  Say that your algorithm only works for values between 1 and 100.  Without constraints, you need to write

```kotlin
fun myFunc(value: Int) {
   if(value !in 1..100) {
      throw IllegalArgumentException("Value out of bounds")
   }
   //real code
}
```

With constraints, it becomes

```kotlin
fun myFunc(@IntRange(1, 100) value: Int) {
   //real code
}
```

The new version lets anyone reading the code or documentation know what the requirements are at a glance, rather than needing to figure it out on the fly.

### Simplicity of testing

Constraints reduce the amount of test code you need to write.  They actually make it impossible to pass an invalid value into an annotated variable.  In the example case, without constraints, myFunc would need at least two additional test cases-  passing in a value less than 1, and passing in a value greater than 100.  Neither of those are needed with constraints, because neither can happen.

### Faster iteration

Most constrained values can be tested at compile time rather than runtime.  For example:

```kotlin
fun myFunc(@IntRange(1, 100) value: Int)  {}
@IntRange(200,500) val a = 500
myfunc(a)  //Error-  no overlap of ranges
```

This will cause a compiler error because it's impossible for a number from [200, 500] to also be in [1, 100].  

There are places where numbers come without a known range.  For example:

```kotlin
fun myFunc(@IntRange(1, 100) value: Int) {
val a = readFromInput()
myFunc(a)  //Error-  unchecked constraint passed to constrained value
```

The way to get around here is 

```kotlin
val a = readFromInput()
myFunc(checkConstraint(a))
```
checkConstraint will check the constraints on the receiver and throw a ConstraintException if they are not satisfied.  This is why you can be assured that a value being written to a variable will always satisfy the constraints.  While this won't be done at compile time (as we can't know the value until runtime), it does throw the exception 1 level up, which can speed triaging it to the right developer in the case of APIs, and its more explicit about what went wrong.  It also still means that myFunc can be written assuming it will never get a bad value

## Using the library

All you need to do is annotate the functions returns, parameters, and variables you wish to constrain with the appropriate constraint.  The library includes a compiler plugin that tracks what annotations are put one each variable and will throw a compiler error if you attempt to assign a value without the right annotation (either directly or inferred) into a receiver with one.  

### What if I need data from some unannotated source?

You have two options in this case-  checkConstraints or checkConstraintsOrDefault.  Both of these functions will check that if a value passed in passes all the constraints for the receiver.  If it does, the value is used.  If it does not, checkConstraints will throw a ConstraintException, and checkConstrainsOrDefault will return a default value.  Think of it like !! and ?: for nullability.  Obviously this means you only have protection at runtime instead of compile time, but it still provides all the other benefits, and is one step closer to the actual problem than checking for a bad value inside the function you wanted to call (or worse, not checking and failing somewhere else).

## Types of constraints

There are a few builtin constraints, plus the ability to build custom ones.

### Numeric constraints
#### Range constraints

Range constraints are @IntRange, @ShortRange, @ByteRange, @LongRange, @FloatRange, and @DoubleRange.  All take a minimum and maximum value, inclusive.  They force the value to be inside that range.  The compiler is fairly smart about this, it can track the valid range of literals coming in (so it knows a 9 is [9,9]) and track ranges across mathematical expressions (so it knows if a is in [1,3] and b is in [5,10], the result is in [6,13]).  It also knows that if you assign a value with range [1,3] to a variable with range [1,10] that it's ok, because [1,10] is a superset.  If the ranges of two values do not overlap at all, it becomes a hard compiler error.  If the two ranges do have overlap but not total overlap, it will cause a compiler error unless you call checkConstraints to validate at runtime.  It will also error if you try to divide by a range including 0.

We also have several aliases for common ranges, such as PositiveInteger (1 or more), NegativeInteger (-1 or less), NonNegativeInteger (0 or more) and NonPositiveInteger (0 or less).  You may also make your own aliases that keep all the power of compiler tracking by making an alias annotation:

```kotlin
@IntRange(myMinVal, myMaxVal)
@Target(
AnnotationTarget.LOCAL_VARIABLE,
AnnotationTarget.PROPERTY,
AnnotationTarget.FIELD,
AnnotationTarget.VALUE_PARAMETER,
AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class MyRange
```

#### Divisibility and remainder

For integral types, we have @DivisibleBy annotations.  These let you specify a variable must be divisible by a certain number, with a required remainder.  So you can require a number to be even, or odd.  Or a multiple of 10.  We also track this across operators in the compiler just like range.  And just like range, you can crate aliases (we created aliases for evens and odds)

### Text constraints

#### CharSequence length

You can put length restrictions using @StringLength.  This can include minimum and maximum lengths.  Again the compiler will track across operators like + when we can.  A helpful @NonEmptyString is included for the common case of wanting a string with data in it

### Collection constraints

Please note that these are only assured to work on immutable collections.  If you use it on a mutable collection and change the data in it, there is currently no mechanism to ensure the new data still matches the constraint.  At best, you'll confuse the compiler, at worst you will be able to pass around invalid data.

#### Collection length

Just like strings, you can create a constraint on the size of a Collection with @CollectionSize

#### Element constraints

Element constraints constrain the value of an element in a collection.  Right now this is a runtime check, and you need to make a custom constraint for it.

## Custom constraints

### Aliasing existing annotations

If you want to make an alias for an existing annotation, like we did with @PositiveInteger, you just need to create a new annotation, and annotate the annotation with the constraint you wish it to alias.  For example

```kotlin
@IntRange(0,9)
@Target(
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class SingleDigit()
```
This will create an annotation that is only satisfied by 0-9.  The @Target and @Retention are important, make sure to add them.

### True custom constraints

This library does make it possible to do custom constraints, but with a caveat.  Since we don't know what the constraint is, it must all be done at runtime.  A custom constraint has two parts: a `Validator` object that does the checking, and a constraint annotation that links to it via `@Constraint`.

First write the validator. It implements `Validator<T, A>`, where `T` is the value type and `A` is the annotation it enforces. `validate` returns nothing -- throw to reject a value, return normally to accept it. It cannot change the value (a constraint validates, it does not coerce).  The signature for Validator is:

```kotlin
interface Validator<T, A : Annotation> {
    fun validate(value: T, annotation: A)
}
```

Then define the constraint annotation and meta-annotate it with `@Constraint(YourValidator::class)`. `@Constraint` is a meta-annotation -- it goes on the annotation class, never directly on a value. The annotation may carry data, which is handed to the validator so it can read its parameters:

```kotlin
@Constraint(InverseRangeValidator::class)
@Target(AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class InverseRange(val min: Int, val max: Int)

object InverseRangeValidator : Validator<Int, InverseRange> {
    override fun validate(value: Int, annotation: InverseRange) {
        if (value in annotation.min..annotation.max)
            throw ConstraintException("must be outside ${annotation.min}..${annotation.max}")
    }
}
```

Now annotate a value with the constraint. Because a runtime validator can never be proven statically, every assignment to a constrained value is a compile error unless you wrap it in `checkConstraint(value)`, which defers the check to runtime:

```kotlin
@InverseRange(0, 10) val x = checkConstraint(20)   // plain `= 20` is a compile error
```

The one case that needs no `checkConstraint` is a transfer from a value already known to satisfy the *same* constraint -- the same annotation class with all arguments equal:

```kotlin
@InverseRange(0, 10) val y = x   // proven: x is already @InverseRange(0, 10)
```

_WARNING-  A VALIDATOR MUST NOT RELY ON ANY DATA OTHER THAN THE VALUE AND ANNOTATION PASSED IN_.  The way the compiler tracks custom constraints, it assumes that if a value passes constraints with a given set of annotation parameters, then it will always pass it for that same value and same set of parameters.  Relying on outside data can break that promised.  The validity of a value for a given constraint should be constant, and determined without any other data.

### Collection element constraints

Collection element constraints work almost identically to normal custom constraints, except you need to use the @ElementConstraint(MyValidator::class) instead of @Constraint.  That tells the compiler to run it on the elements of the collection instead of the collection itself.

## Combining constraints

Constraints can be combined so long as they aren't the same type.  For example, you can make an odd number between 1 and 5 by using 

@IntRange(1,5) @Odd

You cannot repeat two of the same type of constraint-  for example two ranges.  The compiler wouldn't know where to union them or intersect them, and it isn't set up for split ranges.

## Performance concerns

For the most part, these checks run at compile time, so while they may affect build speed they will not affect your running app.  The exception is when you call checkConstraints.  That does happen at runtime, so try to avoid doing it excessively, or having validators that are slow.  The existing validators all boil down to an if statement or two, so should run quickly.

## Installing the library

You must be using at least kotlin 2.2.21.  This version made changes to the compiler plugin system that this library is built on top of.

Simply add the following to your build.gradle:

```
repositories {
mavenCentral()
}

dependencies {
    implementation 'com.gabesechansoftwareconstraints:constraints:0.1.0'
}

configurations
.matching { it.name.startsWith('kotlinCompilerPluginClasspath') }
.configureEach {
    dependencies.add(project.dependencies.create('com.gabesechansoftware.constraints:constraints:1.0.0'))
}
```

If you want to see error in your IDE in realtime without needing to do a gradle build, you must also enable k2 mode.  In IntelliJ, it should be enabled by default.  In Android studio, it needs to be enabled manually.

## Roadmap

Coming in v0.2.0

* Basic string matching constraints
* Improvements to container element constraints-  the new format will be List<@IntArray(0,10) Int>.
* Array<T> support
* Map support

Longer term:

* Add more built in constraints for generally useful scenarios.  Suggestions welcome
* Some way to support mutable Collections, at least for the MutableCollection interface calls
* Open to suggestions
