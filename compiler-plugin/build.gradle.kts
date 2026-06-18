plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The compiler API. `compileOnly` because at run time these classes are
    // already provided by the Kotlin compiler that loads this plugin.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
}
