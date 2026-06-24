plugins {
    kotlin("jvm") version "2.2.21"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

allprojects {
    group = "com.gabesechansoftware.constraints"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Required by the FIR checker's diagnostic-factory delegates (error1, ...).
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    // compileOnly keeps kotlin-compiler-embeddable OUT of the published POM, so consumers
    // don't transitively drag in the entire Kotlin compiler. (The plugin classes reference
    // FIR/IR types, but nothing loads them at a consumer's app runtime.)
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")

    // --- tests ---
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // CompileFailTestRunner compiles the src/test/resources/compile-fail/*.kt files in-process
    // with this module's plugin. output.asPath gives both the compiled classes and the
    // META-INF/services registration the compiler's ServiceLoader needs.
    systemProperty(
        "constraints.plugin.classpath",
        sourceSets.main.get().output.asPath,
    )
}

mavenPublishing {
    // 0.37 dropped the SonatypeHost argument -- Central Portal is the default target now.
    // Add automaticRelease = true to publish without the manual "Publish" click in the portal.
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.gabesechansoftware.constraints", "constraints", version.toString())

    pom {
        name.set("Constraints")
        description.set("Compile-time refinement types for Kotlin (range, divisibility, length, custom constraints) via a K2 compiler plugin.")
        inceptionYear.set("2026")                                  // <-- edit if needed
        url.set("https://github.com/gabesechan/constraints")       // <-- edit to your repo URL
        licenses {
            license {
                name.set("The Apache License, Version 2.0")         // <-- edit if you choose another license
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("gabesechan")                               // <-- edit
                name.set("Gabe Sechan")                            // <-- edit
                url.set("https://github.com/gabesechan")           // <-- edit
            }
        }
        scm {
            url.set("https://github.com/gabesechan/constraints")
            connection.set("scm:git:git://github.com/gabesechan/constraints.git")
            developerConnection.set("scm:git:ssh://git@github.com/gabesechan/constraints.git")
        }
    }
}
