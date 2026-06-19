plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The diagnostic-factory delegates (error1, error0, ...) are
        // context-parameter declarations in Kotlin 2.2, so this module must opt in.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    // The compiler API. `compileOnly` because at run time these classes are
    // already provided by the Kotlin compiler that loads this plugin.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
}
