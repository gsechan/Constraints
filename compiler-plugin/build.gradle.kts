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

    // Tests. The compiler API is compileOnly above, so it must be added explicitly for
    // the test source set (where FIR types are referenced/mocked).
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    // Runtime module needed so compile-fail tests can import @IntRange, @DivisibleBy, etc.
    testImplementation(project(":runtime"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The plugin needs BOTH its compiled classes (build/classes/kotlin/main) AND its
    // resources (build/resources/main, which holds META-INF/services/..CompilerPluginRegistrar
    // -- the file the compiler's ServiceLoader reads to find the plugin). output.asPath
    // includes both, path-separator joined; the runner splits and passes each via -Xplugin=.
    systemProperty(
        "constraints.plugin.classpath",
        sourceSets.main.get().output.asPath
    )
}
