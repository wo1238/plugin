package com.example.pgat

import java.io.File

/**
 * Calcula el nombre del test y resuelve los datos conocidos de las plantillas
 * (PACKAGE / TEST_CLASS_NAME / SOURCE_NAME / CONFIG_PATH).
 */
object TestPromptBuilder {

    data class TestSpec(
        val testClassName: String,
        val testFileName: String,
        val sourceName: String
    )

    data class ParametryTest(val code: String, val stats: ParametryAssertGenerator.Result)

    /** Nombre de clase/archivo de test: SIEMPRE basado en el archivo fuente seleccionado. */
    fun resolveTestSpec(sourceFile: File, fullSource: String): TestSpec {

        val baseName = sourceFile.nameWithoutExtension.ifBlank { "Source" }

        val sourceName = Regex("""(?:class|object)\s+([A-Za-z0-9_]+)""")
            .find(fullSource)
            ?.groupValues
            ?.get(1)
            ?: baseName

        val testClassName = "${baseName}Test"

        return TestSpec(
            testClassName = testClassName,
            testFileName = "$testClassName.scala",
            sourceName = sourceName
        )
    }

    private fun fillHeader(header: String, pkg: String, spec: TestSpec, configPath: String = ""): String =
        header
            .replace("{{PACKAGE}}", pkg)
            .replace("{{TEST_CLASS_NAME}}", spec.testClassName)
            .replace("{{SOURCE_NAME}}", spec.sourceName)
            .replace("{{CONFIG_PATH}}", configPath)

    /** Test de Parametry: asserts locales y la IA solo para los vals ambiguos. */
    fun buildParametryTestCode(
        pkg: String,
        spec: TestSpec,
        fullSource: String,
        askAi: ((String) -> String)? = null
    ): ParametryTest {

        val stats = ParametryAssertGenerator.generate(fullSource, spec.sourceName, askAi)

        val assertsBlock = if (stats.lines.isEmpty()) {
            "    // No se encontraron \"val\" en el archivo fuente."
        } else {
            stats.lines.joinToString("\n") { "    $it" }
        }

        val code = fillHeader(TestTemplates.PARAMETRY_HEADER, pkg, spec)
            .replace("{{ASSERTS}}", assertsBlock)

        return ParametryTest(code, stats)
    }

    /** Header y patrón (GetData o Generate) con los datos conocidos ya resueltos. */
    fun headerAndPattern(testType: String, pkg: String, spec: TestSpec, configPath: String): Pair<String, String> {
        val (header, pattern) = when (testType) {
            "GetData" -> TestTemplates.GET_DATA_HEADER to TestTemplates.GET_DATA_PATTERN
            else -> TestTemplates.GENERATE_HEADER to TestTemplates.GENERATE_PATTERN
        }
        return fillHeader(header, pkg, spec, configPath) to fillHeader(pattern, pkg, spec, configPath)
    }
}