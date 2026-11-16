package com.example.pgat

import java.io.File

/**
 * Calcula el nombre del test y resuelve dinámicamente desde el proyecto los datos
 * de las plantillas (PACKAGE / TEST_CLASS_NAME / SOURCE_NAME / CONFIG_PATH / IMPORTS).
 */
object TestPromptBuilder {

    data class TestSpec(
        val testClassName: String,
        val testFileName: String,
        val sourceName: String
    )

    data class ParametryTest(val code: String, val stats: ParametryAssertGenerator.Result)

    private val PKG_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)""")
    private val OBJECT_DECL_REGEX = Regex("""(?m)^\s*object\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val CONTEXT_PROVIDER_DECL = Regex("""(?m)^\s*(?:trait|class|object)\s+ContextProvider\b""")
    private val CONTEXT_PROVIDER_IMPORT = Regex("""(?m)^\s*import\s+([A-Za-z0-9_.]+\.ContextProvider)\b""")
    private val SKIP_DIRS = setOf("target", ".git", ".idea", "node_modules", "build", "out")

    /** Extrae el package declarado en un código fuente Scala. */
    fun extractPackage(source: String): String =
        PKG_REGEX.find(source)?.groupValues?.get(1)?.trim().orEmpty()

    /**
     * Calcula el `package` de Scala a partir de la ruta del archivo o carpeta donde se va a guardar el test.
     *
     * Ejemplo:
     *   "C:/.../src/test/scala/com/bbva/co/csan/rates/data/pruebas/GetDataRateTest.scala"
     *   -> "com.bbva.co.csan.rates.data.pruebas"
     *
     * Soporta tanto File de carpeta como File de archivo .scala, o un String con ruta/paquete.
     */
    fun packageFromOutputPath(outputFileOrDir: File?, fallbackPkg: String = ""): String {
        if (outputFileOrDir == null) return normalizePkgString(fallbackPkg)
        val dir = if (outputFileOrDir.name.endsWith(".scala", ignoreCase = true) ||
            outputFileOrDir.name.endsWith(".draft", ignoreCase = true)
        ) {
            outputFileOrDir.parentFile ?: outputFileOrDir
        } else {
            outputFileOrDir
        }
        return packageFromPathString(dir.path, fallbackPkg)
    }

    /**
     * Convierte una cadena (que puede ser una ruta como `src/test/scala/com/bbva/.../pruebas`
     * o ya un paquete `com.bbva...`) en un nombre de `package` válido de Scala.
     */
    fun packageFromPathString(rawPathOrPkg: String, fallbackPkg: String = ""): String {
        val trimmed = rawPathOrPkg.trim()
        if (trimmed.isEmpty()) return normalizePkgString(fallbackPkg)

        // Si no tiene barras y ya parece un package válido (ej. com.bbva.co.csan.rates.data.pruebas)
        if (!trimmed.contains('/') && !trimmed.contains('\\') && !trimmed.endsWith(".scala", ignoreCase = true)) {
            return normalizePkgString(trimmed)
        }

        var p = trimmed.replace("\\", "/").trimEnd('/')
        // Si incluye nombre de archivo (.scala o .draft), nos quedamos solo con su carpeta
        if (p.endsWith(".scala", ignoreCase = true) || p.endsWith(".draft", ignoreCase = true)) {
            p = p.substringBeforeLast('/', "")
        }

        // 1) Cortar después de src/test/scala/, src/main/scala/, src/test/java/, test/scala/, etc.
        val srcMarker = Regex("""(?:^|/)(?:src/)?(?:test|main|it)/(?:scala|java|kotlin)(?:-[0-9.]+)?/(.+)$""", RegexOption.IGNORE_CASE)
            .find(p)
        if (srcMarker != null) {
            val rel = srcMarker.groupValues[1].trim('/')
            val pkg = rel.split('/').filter { it.isNotBlank() }.joinToString(".")
            if (pkg.isNotEmpty()) return pkg
        }

        // 2) Cortar desde el primer segmento raíz de paquete estándar (com, org, co, net, io, edu, gov)
        val segs = p.split('/').filter { it.isNotBlank() }
        val roots = setOf("com", "org", "co", "net", "io", "edu", "gov", "es", "mx", "pe", "ar")
        val rootIdx = segs.indexOfFirst { it.lowercase() in roots }
        if (rootIdx >= 0) {
            return segs.drop(rootIdx).joinToString(".")
        }

        return normalizePkgString(fallbackPkg)
    }

    private fun normalizePkgString(raw: String): String =
        raw.trim().removePrefix("package ").trim().trim('.')

    /** Lista todos los archivos .scala del proyecto de forma segura (respetando rutas largas). */
    private fun projectScalaFiles(basePath: File?, sourceFile: File? = null): List<File> {
        val roots = LinkedHashSet<File>()
        if (basePath != null) roots.add(LongPaths.resolve(basePath, true))
        if (sourceFile != null) {
            var cur = sourceFile.absoluteFile.parentFile
            var steps = 0
            while (cur != null && steps < 8) {
                if (File(cur, "src").isDirectory || File(cur, "pom.xml").isFile || File(cur, "build.sbt").isFile) {
                    roots.add(LongPaths.resolve(cur, true))
                }
                cur = cur.parentFile
                steps++
            }
        }
        val out = ArrayList<File>()
        val seen = HashSet<String>()
        for (r in roots) {
            if (!r.isDirectory) continue
            try {
                r.walkTopDown()
                    .onEnter { it == r || it.name !in SKIP_DIRS }
                    .forEach { f ->
                        if (f.isFile && f.extension == "scala" && seen.add(f.absolutePath.lowercase())) {
                            out.add(f)
                        }
                    }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * Descubre el import real de ContextProvider buscando en el proyecto:
     *  1) El archivo .scala que declara `trait/class/object ContextProvider` -> usa su `package`.
     *  2) Si no existe como archivo propio, busca cómo lo importan otros tests del proyecto (`import ...ContextProvider`).
     *  3) Si no aparece en ningún archivo, usa el paquete raíz común del módulo fuente.
     */
    fun resolveContextProviderImport(
        testPkg: String,
        sourcePkg: String,
        basePath: File? = null,
        sourceFile: File? = null
    ): String {
        val scalaFiles = projectScalaFiles(basePath, sourceFile)

        // 1) Buscar declaración directa de ContextProvider (primero por nombre de archivo, luego por contenido)
        val byName = scalaFiles.firstOrNull { it.nameWithoutExtension == "ContextProvider" }
        if (byName != null) {
            val cpPkg = extractPackage(byName.readText()).ifEmpty {
                packageFromOutputPath(byName)
            }
            if (cpPkg.isNotEmpty()) {
                return if (cpPkg == testPkg) "" else "import $cpPkg.ContextProvider"
            }
        }

        for (f in scalaFiles) {
            val text = try { f.readText() } catch (_: Exception) { continue }
            if (CONTEXT_PROVIDER_DECL.containsMatchIn(text)) {
                val cpPkg = extractPackage(text).ifEmpty { packageFromOutputPath(f) }
                if (cpPkg.isNotEmpty()) {
                    return if (cpPkg == testPkg) "" else "import $cpPkg.ContextProvider"
                }
            }
        }

        // 2) Buscar cómo lo importa cualquier otro test existente en el proyecto
        for (f in scalaFiles) {
            val text = try { f.readText() } catch (_: Exception) { continue }
            val m = CONTEXT_PROVIDER_IMPORT.find(text)
            if (m != null) {
                val fullImp = m.groupValues[1]
                val impPkg = fullImp.substringBeforeLast('.', "")
                return if (impPkg == testPkg) "" else "import $fullImp"
            }
        }

        // 3) Fallback dinámico basado en el paquete del archivo fuente (padre del paquete de datos/proceso)
        val refPkg = sourcePkg.ifEmpty { testPkg }
        val segs = refPkg.split('.').filter { it.isNotEmpty() }
        val fallbackPkg = if (segs.size > 1) segs.dropLast(1).joinToString(".") else refPkg
        return if (fallbackPkg.isEmpty() || fallbackPkg == testPkg) "" else "import $fallbackPkg.ContextProvider"
    }

    /**
     * Descubre dinámicamente todos los imports adicionales que necesita el test a partir del proyecto:
     *  - Para Generate: importa la clase GetData asociada si está en un paquete distinto al del test (`testPkg`).
     *  - Conserva los imports wildcard (`._`) que ya tenga la clase fuente (resolviendo rutas relativas con `sourcePkg`).
     *  - Si la clase fuente importa un `object` de constantes del proyecto o usa constantes de un `object` en su mismo
     *    paquete, agrega `import <pkg>.<Objeto>._`.
     *  - Si la clase fuente usa `org.apache.spark.sql.functions._`, lo incluye.
     */
    fun resolveExtraImports(
        testType: String,
        testPkg: String,
        fullSource: String,
        spec: TestSpec,
        basePath: File? = null,
        sourceFile: File? = null
    ): List<String> {
        if (testType == "Parametry") return emptyList()

        val sourcePkg = extractPackage(fullSource).ifEmpty {
            sourceFile?.let { packageFromOutputPath(it) }.orEmpty()
        }
        val scalaFiles = projectScalaFiles(basePath, sourceFile)
        val usedConstants = CallSiteResolver.extractConstantTokens(fullSource)

        data class ProjectObject(val name: String, val pkg: String, val vals: Set<String>, val file: File)
        val projectObjects = ArrayList<ProjectObject>()
        val getDataClasses = ArrayList<Pair<String, String>>() // (className, pkg)
        val mapDef = Regex("""def\s+\w+[^=\n]*Map\[\s*String\s*,\s*DataFrame\s*\]""")

        for (f in scalaFiles) {
            if (f.nameWithoutExtension == spec.sourceName || f.nameWithoutExtension == spec.testClassName) continue
            if (f.path.replace("\\", "/").contains("/src/test/")) continue
            val text = try { f.readText() } catch (_: Exception) { continue }
            val fPkg = extractPackage(text).ifEmpty { packageFromOutputPath(f) }

            val objMatch = OBJECT_DECL_REGEX.find(text)
            if (objMatch != null) {
                val objName = objMatch.groupValues[1]
                val vals = ParametryAssertGenerator.extractVals(text).map { it.name }.toSet()
                projectObjects.add(ProjectObject(objName, fPkg, vals, f))
            }

            if (testType == "Generate" && mapDef.containsMatchIn(text)) {
                getDataClasses.add(f.nameWithoutExtension to fPkg)
            }
        }

        val result = LinkedHashSet<String>()

        // Si es un test de Generate y la clase GetData está en otro paquete respecto a testPkg, importarla
        if (testType == "Generate") {
            val core = spec.sourceName.substringAfter("Generate", spec.sourceName).removePrefix("Data")
            val pickedGetData = getDataClasses.firstOrNull { it.first == "GetData$core" }
                ?: getDataClasses.firstOrNull { it.first.startsWith("GetData") && it.first.contains(core) }
                ?: getDataClasses.firstOrNull()
            if (pickedGetData != null && pickedGetData.second.isNotEmpty() && pickedGetData.second != testPkg) {
                result.add("import ${pickedGetData.second}.${pickedGetData.first}")
            }
        }

        // 1) Revisar imports explícitos del archivo fuente
        val rawImports = fullSource.lines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim() }

        for (imp in rawImports) {
            when {
                imp.endsWith("._") -> {
                    val prefix = imp.removeSuffix("._")
                    val resolved = if (!prefix.contains('.') && sourcePkg.isNotEmpty()) {
                        "$sourcePkg.$imp"
                    } else {
                        imp
                    }
                    result.add("import $resolved")
                }
                !imp.contains("{") -> {
                    val shortName = imp.substringAfterLast('.')
                    val matchingObj = projectObjects.firstOrNull { it.name == shortName && it.vals.isNotEmpty() }
                    if (matchingObj != null) {
                        val fullObjPkg = if (imp.contains('.')) imp.substringBeforeLast('.') else matchingObj.pkg
                        if (fullObjPkg.isNotEmpty()) {
                            result.add("import $fullObjPkg.${matchingObj.name}._")
                        }
                    } else if (!imp.contains('.') && sourcePkg.isNotEmpty() && sourcePkg != testPkg) {
                        // Import relativo de una clase del mismo paquete que el fuente
                        result.add("import $sourcePkg.$imp")
                    }
                }
            }
        }

        // 2) Detectar objects del proyecto cuyas constantes se usan en el código fuente sin import explícito
        if (usedConstants.isNotEmpty()) {
            for (obj in projectObjects) {
                if (obj.vals.isNotEmpty() && obj.vals.any { it in usedConstants }) {
                    val qualified = if (obj.pkg.isNotEmpty()) "${obj.pkg}.${obj.name}._" else "${obj.name}._"
                    result.add("import $qualified")
                }
            }
        }

        // 3) Si el fuente usa funciones de Spark SQL (o las importa), asegurar import org.apache.spark.sql.functions._
        if (rawImports.any { it.startsWith("org.apache.spark.sql.functions") } ||
            Regex("""\b(?:col|lit|trim|when|concat|lpad|max|min|sum|to_date|date_format)\s*\(""").containsMatchIn(fullSource)
        ) {
            result.add("import org.apache.spark.sql.functions._")
        }

        return result.toList()
    }

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

    private fun fillHeader(
        testType: String,
        header: String,
        pkgOrOutputPath: String,
        spec: TestSpec,
        configPath: String = "",
        fullSource: String = "",
        basePath: File? = null,
        sourceFile: File? = null,
        outputFileOrDir: File? = null
    ): String {
        val sourcePkg = extractPackage(fullSource).ifEmpty {
            sourceFile?.let { packageFromOutputPath(it) }.orEmpty()
        }
        // El package del test se toma de la ruta de salida si se proporcionó (como File o como ruta en pkgOrOutputPath)
        val resolvedTestPkg = if (outputFileOrDir != null) {
            packageFromOutputPath(outputFileOrDir, pkgOrOutputPath)
        } else {
            packageFromPathString(pkgOrOutputPath, sourcePkg)
        }

        val cpImport = resolveContextProviderImport(resolvedTestPkg, sourcePkg, basePath, sourceFile)
        // Si el test va en un paquete distinto al del archivo fuente (ej. .pruebas), importa la clase/object bajo prueba
        val sourceClassImport = if (sourcePkg.isNotEmpty() && sourcePkg != resolvedTestPkg) {
            "import $sourcePkg.${spec.sourceName}"
        } else {
            ""
        }
        val extraImports = resolveExtraImports(testType, resolvedTestPkg, fullSource, spec, basePath, sourceFile)
            .joinToString("\n")

        val filled = header
            .replace("{{PACKAGE}}", resolvedTestPkg)
            .replace("{{TEST_CLASS_NAME}}", spec.testClassName)
            .replace("{{SOURCE_NAME}}", spec.sourceName)
            .replace("{{CONFIG_PATH}}", configPath)
            .replace("{{CONTEXT_PROVIDER_IMPORT}}", cpImport)
            .replace("{{SOURCE_CLASS_IMPORT}}", sourceClassImport)
            .replace("{{EXTRA_IMPORTS}}", extraImports)

        return filled
            .lines()
            .fold(ArrayList<String>()) { acc, line ->
                if (line.isBlank() && acc.lastOrNull()?.isBlank() == true) {
                    acc
                } else {
                    acc.add(line)
                    acc
                }
            }
            .joinToString("\n")
    }

    /**
     * Test de Parametry: asserts locales y la IA solo para los vals ambiguos.
     * `pkg` puede ser el nombre del paquete o directamente la ruta donde se guardará el archivo de test.
     * `askAi` se mantiene como último parámetro para permitir trailing lambdas.
     */
    fun buildParametryTestCode(
        pkg: String,
        spec: TestSpec,
        fullSource: String,
        basePath: File? = null,
        sourceFile: File? = null,
        outputFileOrDir: File? = null,
        askAi: ((String) -> String)? = null
    ): ParametryTest {

        val stats = ParametryAssertGenerator.generate(fullSource, spec.sourceName, askAi)

        val assertsBlock = if (stats.lines.isEmpty()) {
            "    // No se encontraron \"val\" en el archivo fuente."
        } else {
            stats.lines.joinToString("\n") { "    $it" }
        }

        val code = fillHeader(
            testType = "Parametry",
            header = TestTemplates.PARAMETRY_HEADER,
            pkgOrOutputPath = pkg,
            spec = spec,
            configPath = "",
            fullSource = fullSource,
            basePath = basePath,
            sourceFile = sourceFile,
            outputFileOrDir = outputFileOrDir
        ).replace("{{ASSERTS}}", assertsBlock)

        return ParametryTest(code, stats)
    }

    /** Header y patrón (GetData o Generate) con el package de destino y los imports del proyecto ya resueltos. */
    fun headerAndPattern(
        testType: String,
        pkg: String,
        spec: TestSpec,
        configPath: String,
        fullSource: String = "",
        basePath: File? = null,
        sourceFile: File? = null,
        outputFileOrDir: File? = null
    ): Pair<String, String> {
        val (header, pattern) = when (testType) {
            "GetData" -> TestTemplates.GET_DATA_HEADER to TestTemplates.GET_DATA_PATTERN
            else -> TestTemplates.GENERATE_HEADER to TestTemplates.GENERATE_PATTERN
        }
        return fillHeader(testType, header, pkg, spec, configPath, fullSource, basePath, sourceFile, outputFileOrDir) to
                fillHeader(testType, pattern, pkg, spec, configPath, fullSource, basePath, sourceFile, outputFileOrDir)
    }
}