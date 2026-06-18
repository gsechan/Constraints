import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// Resolves to just the compiler-plugin jar (no transitive deps), so we can pass
// it to the Kotlin compiler with -Xplugin.
val constraintsPlugin: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":runtime"))
    constraintsPlugin(project(":compiler-plugin"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Apply the constraints compiler plugin to the test source set (that is where
// the demonstrating code lives).
tasks.named<KotlinCompile>("compileTestKotlin") {
    dependsOn(constraintsPlugin)
    val pluginClasspath = constraintsPlugin
    compilerOptions.freeCompilerArgs.add(
        provider { "-Xplugin=${pluginClasspath.singleFile.absolutePath}" }
    )
}
