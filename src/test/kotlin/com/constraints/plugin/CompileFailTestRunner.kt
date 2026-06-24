package com.constraints.plugin

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Discovers every `.kt` file under `src/test/resources/compile-fail/`, compiles it
 * in-process with the constraints plugin, and verifies that:
 *  1. Compilation FAILS (the file contains intentionally invalid code).
 *  2. Every line marked `// ERROR: <pattern>` produces a compiler diagnostic whose
 *     message contains `<pattern>` (case-sensitive substring match).
 *
 * The plugin is found by scanning the test classpath for the directory or jar that
 * contains the Kotlin compiler plugin service registration file, so the test works
 * in both Gradle and IntelliJ without any extra configuration.
 *
 * Convention: place an `// ERROR: <substring>` comment on the same line as the code
 * expected to fail. The substring is matched against the actual error message text.
 * Each file becomes a separate named test in the JUnit5 output.
 */
class CompileFailTestRunner {

    @TestFactory
    fun compileFailTests(): List<DynamicTest> {
        val resourceDir = javaClass.classLoader.getResource("compile-fail")
            ?: error("compile-fail resource directory not found -- does src/test/resources/compile-fail/ exist?")

        return File(resourceDir.toURI())
            .walkTopDown()
            .filter { it.extension == "kt" }
            .sortedBy { it.name }
            .map { file -> DynamicTest.dynamicTest(file.nameWithoutExtension) { runTest(file) } }
            .toList()
    }

    // -------------------------------------------------------------------------
    // Test execution
    // -------------------------------------------------------------------------

    private fun runTest(file: File) {
        val lines = file.readLines()

        // Collect expected errors: (1-based line number, expected substring)
        val expected = lines.mapIndexedNotNull { idx, line ->
            val tag = "// ERROR:"
            val pos = line.indexOf(tag)
            if (pos >= 0) (idx + 1) to line.substring(pos + tag.length).trim() else null
        }

        require(expected.isNotEmpty()) {
            "${file.name}: no `// ERROR:` markers found -- every compile-fail file must have at least one"
        }

        val (succeeded, errorMessages) = compile(file)

        assertTrue(!succeeded,
            "${file.name}: expected compilation to FAIL but it SUCCEEDED")

        val allOutput = errorMessages.joinToString("\n") { "  $it" }

        for ((line, pattern) in expected) {
            assertTrue(
                errorMessages.any { pattern in it },
                "${file.name}:$line: expected error not found.\n  Pattern : $pattern\n  Got:\n$allOutput"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Compilation via K2JVMCompiler CLI (no IntelliJ platform imports needed)
    // -------------------------------------------------------------------------

    /**
     * Returns (succeeded, listOfDiagnosticLines) where the list contains every
     * non-blank compiler output line. In K2 2.x, diagnostics go to [System.err]
     * regardless of the [PrintStream] passed to [K2JVMCompiler.exec], so we
     * redirect [System.err] for the duration of compilation to capture everything.
     *
     * Note: this is not thread-safe; JUnit5 runs [DynamicTest]s sequentially by
     * default so it is fine here.
     */
    private fun compile(file: File): Pair<Boolean, List<String>> {
        val captured = ByteArrayOutputStream()
        val ps = PrintStream(captured)
        val outputDir = Files.createTempDirectory("compile-fail-out").toFile()
        // Capture System.err as well: K2 routes diagnostics there, not to the
        // PrintStream argument of exec() despite the API suggesting otherwise.
        val savedErr = System.err
        System.setErr(ps)
        try {
            val args = buildList {
                add("-classpath"); add(System.getProperty("java.class.path"))
                // One -Xplugin= per path entry. The plugin needs both its classes dir and its
                // resources dir (which holds META-INF/services/..CompilerPluginRegistrar).
                // Repeating the flag accumulates entries regardless of delimiter handling, and
                // K2 requires the '=' form for advanced options.
                pluginPaths.forEach { add("-Xplugin=$it") }
                // stdlib is already on -classpath above; suppress "can't find kotlin-home" warnings.
                add("-no-stdlib")
                add("-no-reflect")
                add("-d"); add(outputDir.absolutePath)
                add(file.absolutePath)
            }
            val exitCode = K2JVMCompiler().exec(ps, *args.toTypedArray())
            ps.flush()
            // Collect every non-blank line -- the exact format of K2 error lines
            // varies by version, so matching on our plugin's distinctive message
            // substrings (e.g. "ranges do not overlap") is more reliable than
            // filtering by "error:" prefix.
            val lines = captured.toString().lines().filter { it.isNotBlank() }
            return (exitCode == ExitCode.OK) to lines
        } finally {
            System.setErr(savedErr)
            outputDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Plugin path discovery
    // -------------------------------------------------------------------------

    /**
     * The classpath entries that, together, let the compiler load the plugin: the dir with the
     * registrar CLASS plus the dir with the META-INF/services registration FILE (under Gradle these
     * are two separate dirs -- build/classes/kotlin/main and build/resources/main).
     */
    private val pluginPaths: List<String> by lazy {
        // Gradle sets this to output.asPath (classes + resources, path-separator joined).
        val fromProperty = System.getProperty("constraints.plugin.classpath")
            ?.split(File.pathSeparator)
            ?.filter { File(it).exists() }
            ?.takeIf { it.isNotEmpty() }
        if (fromProperty != null) return@lazy fromProperty

        // IntelliJ fallback: collect every classpath entry that holds either the service
        // registration file or the registrar class. Covers both same-dir (native IntelliJ
        // out/production) and split-dir (Gradle) layouts.
        val serviceRel = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val classRel = "com/constraints/plugin/ConstraintsComponentRegistrar.class"
        val found = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { entry ->
                File(entry, serviceRel).let { it.exists() && "ConstraintsComponentRegistrar" in it.readText() } ||
                    File(entry, classRel).exists()
            }
            .distinct()
        check(found.isNotEmpty()) {
            "Cannot find the constraints compiler plugin. Run via Gradle (which sets " +
                "'constraints.plugin.classpath') or ensure the plugin's build output is on the test classpath."
        }
        found
    }
}
