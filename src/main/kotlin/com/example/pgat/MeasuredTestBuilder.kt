package com.example.pgat

import java.io.File

/**
 * Genera tests de GetData / Generate con números MEDIDOS:
 *  - La IA devuelve sondas (código Spark) + el test con placeholders {{ROWS:k}} {{COLS:k}} {{MAPSIZE:m}}.
 *  - ParquetProbe ejecuta las sondas sobre los parquet de muestra.
 *  - Aquí se verifica la respuesta, se reintenta una vez si hay problemas y se sustituyen los números.
 */
object MeasuredTestBuilder {

    data class Output(val code: String, val measured: List<String>, val problems: List<String>)

    data class Method(
        val name: String,
        val params: String,
        val returnType: String,
        val isPrivate: Boolean,
        val body: String
    )

    private data class Parsed(val probes: List<ParquetProbe.Probe>, val test: String)
    private data class Attempt(val parsed: Parsed, val res: ParquetProbe.RunResult, val issues: List<String>)
    private data class Companions(val imported: List<File>, val siblings: List<File>)
    private class Ctx(
        val testable: List<Method>,
        val preamble: String,
        val infos: List<ParquetProbe.Info>,
        val workDir: File,
        val askAi: (String) -> String
    )

    private val PH = Regex("""\{\{(ROWS|COLS|MAPSIZE):([A-Za-z0-9_]+)\}\}""")
    private val LEFTOVER = Regex("""\{\{[A-Za-z_]+(:[A-Za-z0-9_]*)?\}\}""")
    private val HARDCODED = Regex("""(count\(\)|\.columns\.length|\.size|\.length)\s*(==|shouldBe|should\s+be)\s*\d+""")
    private val PQ_REF = Regex("""PQ\("([^"]+)"\)""")

    // ------------------------------------------------------------------
    // Análisis del código fuente
    // ------------------------------------------------------------------

    private val DEF_REGEX = Regex(
        """(?m)^([ \t]*)((?:(?:private|protected|override|final)(?:\[[^\]]*\])?[ \t]+)*)def[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*(\((?:[^()]|\([^()]*\))*\))?[ \t]*(?::[ \t]*([^=\n{]+?))?[ \t]*="""
    )

    fun methods(src: String): List<Method> {
        val all = DEF_REGEX.findAll(src).toList()
        if (all.isEmpty()) return emptyList()
        val minIndent = all.minOf { it.groupValues[1].length }
        val top = all.filter { it.groupValues[1].length == minIndent }
        return top.mapIndexed { i, m ->
            val end = if (i + 1 < top.size) top[i + 1].range.first else src.length
            Method(
                name = m.groupValues[3],
                params = m.groupValues[4].removePrefix("(").removeSuffix(")").trim().replace(Regex("""\s+"""), " "),
                returnType = m.groupValues[5].trim(),
                isPrivate = Regex("""private(?!\[)""").containsMatchIn(m.groupValues[2]),
                body = src.substring(m.range.last + 1, end)
            )
        }
    }

    private fun balancedInner(s: String, open: Int): String? {
        var depth = 0
        var inStr = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> {
                        depth--
                        if (depth == 0) return s.substring(open + 1, i)
                    }
                }
            }
            i++
        }
        return null
    }

    /** Cantidad exacta de entradas del Map(k -> v, ...) literal que arma el método. */
    private fun mapSize(m: Method): Int? {
        val found = Regex("""\bMap\s*(\[[^\]]*\])?\s*\(""").find(m.body) ?: return null
        val inner = balancedInner(m.body, found.range.last) ?: return null
        if (!inner.contains("->")) return null
        return ParametryAssertGenerator.countTopLevelElements(inner)
    }

    private fun findCompanions(basePath: File, sourceFile: File, fullSource: String, selfName: String): Companions {
        val names = fullSource.lines().map { it.trim() }.filter { it.startsWith("import ") }
            .mapNotNull { line ->
                line.removePrefix("import ").split('.').lastOrNull { seg ->
                    seg.isNotEmpty() && seg[0].isUpperCase() && seg.all { it.isLetterOrDigit() || it == '_' }
                }
            }
            .distinct()
            .filter { it != selfName }

        val skip = setOf("target", ".git", ".idea", "node_modules", "build", "out")
        val index = HashMap<String, File>()
        try {
            basePath.walkTopDown().onEnter { it.name !in skip }.forEach { f ->
                if (f.isFile && f.extension == "scala") index.putIfAbsent(f.nameWithoutExtension, f)
            }
        } catch (ex: Exception) {
            // sin índice: la IA trabaja solo con el código fuente
        }
        val imported = names.mapNotNull { index[it] }.take(3)

        val mapDef = Regex("""def\s+\w+[^=\n]*Map\[\s*String\s*,\s*DataFrame\s*\]""")
        val siblings = (sourceFile.parentFile?.listFiles() ?: emptyArray<File>())
            .filter { f ->
                f.isFile && f.extension == "scala" &&
                        f.absolutePath != sourceFile.absolutePath &&
                        imported.none { it.absolutePath == f.absolutePath } &&
                        mapDef.containsMatchIn(f.readText())
            }
            .take(2)

        return Companions(imported, siblings)
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    private fun buildPrompt(
        testType: String,
        pkg: String,
        spec: TestPromptBuilder.TestSpec,
        header: String,
        pattern: String,
        code: String,
        companionsText: String,
        configText: String,
        infos: List<ParquetProbe.Info>,
        testable: List<Method>,
        mapSizes: Map<String, Int>,
        hasPreamble: Boolean
    ): String {

        val parquetLines = infos.joinToString("\n") { i ->
            val cols = if (i.columns.size > 60) {
                i.columns.take(60).joinToString(",") + ",... (${i.columns.size} en total)"
            } else {
                i.columns.joinToString(",")
            }
            "- nombre: ${i.name} | ruta relativa: ${i.relPath} | filas: ${i.rows} | columnas(${i.columns.size}): $cols"
        }

        val methodLines = testable.joinToString("\n") { m ->
            val extra = if (m.returnType.contains("Map[")) {
                if (mapSizes.containsKey(m.name)) "  -> tamaño del Map ya calculado: usa {{MAPSIZE:${m.name}}}"
                else "  -> usa {{MAPSIZE:${m.name}}}"
            } else ""
            "- ${m.name}(${m.params}): ${m.returnType}$extra"
        }

        val preambleNote = if (hasPreamble)
            "- Las constantes de los objects asociados ya están importadas (puedes usar sus nombres directamente)."
        else
            "- No hay objects de constantes importables: usa literales tomados del código."

        return listOf(
            "Eres un experto en Scala, Apache Spark y ScalaTest. NO HABLES. NO EXPLIQUES. Sin bloques markdown.",
            "",
            "TIPO DE TEST: $testType",
            "",
            "CÓDIGO A PROBAR (clase ${spec.sourceName}, package $pkg):",
            code,
            "",
            "ARCHIVOS ASOCIADOS (constantes y clases relacionadas):",
            companionsText.ifBlank { "(no se encontraron)" },
            "",
            "ARCHIVO .conf DEL TEST (úsalo solo para conocer valores de config como año o mes):",
            configText.ifBlank { "(vacío)" },
            "",
            "PARQUET DE MUESTRA, YA LEÍDOS CON SPARK (filas y columnas reales):",
            parquetLines,
            "",
            "MÉTODOS QUE DEBES CUBRIR (TODOS, ninguno se puede omitir):",
            methodLines,
            "",
            "TU RESPUESTA TIENE DOS PARTES.",
            "",
            "PARTE 1 - SONDAS. Por cada resultado (DataFrame) cuyas filas/columnas haya que medir escribe:",
            "@@PROBE <clave>",
            "<código Scala/Spark que reproduce el método sobre los parquet y termina definiendo: val result: DataFrame = ...>",
            "",
            "Reglas de las sondas:",
            "- Corren en un script Scala independiente SIN las clases del proyecto. Ya existen: spark (SparkSession),",
            "  import org.apache.spark.sql.functions._, import spark.implicits._ y PQ: Map[String, String] con la ruta",
            "  de cada parquet por su nombre.",
            preambleNote,
            "- Lee datos SOLO con spark.read.parquet(PQ(\"nombre\")) usando nombres de la lista de parquet de arriba.",
            "  No uses ReaderWithDataproc ni inventes rutas.",
            "- Elige el parquet que corresponde a la constante o parámetro con que se llama al método",
            "  (por nombre y por columnas). Si el método usa varios inputs, lee cada uno.",
            "- Copia la lógica del método tal cual (mismos select, trim, filtros, joins, fechas).",
            "  Si usa config.getString(X), reemplázalo por el valor literal que figura en el .conf.",
            "- La clave es única (letras, números y _). Un método puede tener varias claves.",
            "",
            "PARTE 2 - TEST:",
            "@@TEST",
            "<la clase de test completa>",
            "",
            "Reglas del test:",
            "1. Usa EXACTAMENTE este header (ya tiene package, imports base, clase y config correctos; no los cambies,",
            "   solo agrega los imports adicionales que realmente uses):",
            "",
            header,
            "",
            "2. Sigue este patrón para el contenido de los tests:",
            "",
            pattern,
            "",
            "3. PROHIBIDO escribir a mano cantidades de filas, columnas o elementos. Usa SOLO los placeholders",
            "   {{ROWS:clave}}, {{COLS:clave}} (claves de tus sondas) y {{MAPSIZE:metodo}}; el sistema los reemplaza por los",
            "   valores medidos. Escribe {{N}} como número de cada test; el sistema numera.",
            "4. Un test por cada método de la lista. No inventes métodos, constantes ni rutas.",
            "5. Sin TODO ni comentarios de relleno."
        ).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Respuesta de la IA
    // ------------------------------------------------------------------

    private fun parse(resp: String): Parsed {
        val probes = ArrayList<ParquetProbe.Probe>()
        val test = StringBuilder()
        val code = StringBuilder()
        var mode = 0
        var key = ""

        fun flush() {
            if (mode == 1 && key.isNotEmpty() && code.isNotBlank()) {
                probes.add(ParquetProbe.Probe(key, code.toString().trimEnd()))
            }
            code.setLength(0)
        }

        for (line in resp.lines()) {
            val t = line.trim()
            when {
                t.startsWith("@@PROBE") -> {
                    flush()
                    mode = 1
                    key = t.removePrefix("@@PROBE").trim().replace(Regex("[^A-Za-z0-9_]"), "")
                }
                t.startsWith("@@TEST") -> {
                    flush()
                    mode = 2
                }
                mode == 1 -> code.appendLine(line)
                mode == 2 -> test.appendLine(line)
            }
        }
        flush()
        return Parsed(probes.distinctBy { it.key }, test.toString().trim())
    }

    private fun staticIssues(p: Parsed, testable: List<Method>): List<String> {
        val out = ArrayList<String>()
        if (!Regex("""class\s+\w+\s+extends""").containsMatchIn(p.test)) {
            out.add("La parte @@TEST no contiene la clase de test completa.")
            return out
        }
        for (m in testable) {
            if (!Regex("""\b${Regex.escape(m.name)}\b""").containsMatchIn(p.test)) {
                out.add("No hay test para el método ${m.name}.")
            }
        }
        if (HARDCODED.containsMatchIn(p.test)) {
            out.add("Hay cantidades escritas a mano; usa solo {{ROWS:..}}, {{COLS:..}} y {{MAPSIZE:..}}.")
        }
        if (p.test.contains("TODO")) out.add("El test contiene TODO.")
        val keys = p.probes.map { it.key }.toSet()
        PH.findAll(p.test).forEach { m ->
            if (m.groupValues[1] != "MAPSIZE" && m.groupValues[2] !in keys) {
                out.add("El placeholder ${m.value} no tiene sonda con esa clave.")
            }
        }
        val leftover = LEFTOVER.findAll(PH.replace(p.test, "").replace("{{N}}", "")).toList()
        if (leftover.isNotEmpty()) {
            out.add("Placeholders no reconocidos: ${leftover.joinToString(", ") { it.value }}")
        }
        return out.distinct()
    }

    private fun attempt(prompt: String, ctx: Ctx): Attempt {
        val parsed = parse(ctx.askAi(prompt))
        val issues = ArrayList(staticIssues(parsed, ctx.testable))
        val res = ParquetProbe.runProbes(parsed.probes, ctx.preamble, ctx.infos, ctx.workDir)

        val probeKeys = parsed.probes.map { it.key }.toSet()
        val used = PH.findAll(parsed.test)
            .filter { it.groupValues[1] != "MAPSIZE" }
            .map { it.groupValues[2] }
            .toSet()
        used.filter { it in probeKeys && it !in res.measures }.forEach {
            issues.add("La sonda '$it' falló: ${res.errors[it] ?: "sin resultado"}")
        }
        return Attempt(parsed, res, issues.distinct())
    }

    // ------------------------------------------------------------------
    // Armado final
    // ------------------------------------------------------------------

    private fun substitute(
        test: String,
        res: ParquetProbe.RunResult,
        mapSizes: Map<String, Int>,
        problems: MutableList<String>
    ): String {
        var out = PH.replace(test) { m ->
            val kind = m.groupValues[1]
            val key = m.groupValues[2]
            val value: String? = when (kind) {
                "ROWS" -> res.measures[key]?.rows?.toString()
                "COLS" -> res.measures[key]?.cols?.toString()
                else -> mapSizes[key]?.toString()
            }
            if (value != null) {
                value
            } else {
                problems.add("No se pudo medir $kind de '$key'.")
                "-1 /* NO MEDIDO: $key */"
            }
        }
        var n = 0
        out = Regex("""\{\{N\}\}""").replace(out) {
            n++
            n.toString()
        }
        return out
    }

    /** Agrega los imports con comodín del código fuente (constantes) si la IA los olvidó. */
    private fun addWildcardImports(test: String, fullSource: String): String {
        val wanted = fullSource.lines().map { it.trim() }.filter { it.startsWith("import ") && it.endsWith("._") }
        val lines = test.lines().toMutableList()
        val missing = wanted.filter { w -> lines.none { it.trim() == w } }
        if (missing.isEmpty()) return test
        val idx = lines.indexOfLast { it.trim().startsWith("import ") }
        if (idx < 0) return test
        lines.addAll(idx + 1, missing)
        return lines.joinToString("\n")
    }

    fun build(
        testType: String,
        pkg: String,
        spec: TestPromptBuilder.TestSpec,
        code: String,
        fullSource: String,
        sourceFile: File,
        basePath: File,
        configRel: String,
        configText: String,
        infos: List<ParquetProbe.Info>,
        focus: String,
        progress: (String) -> Unit,
        askAi: (String) -> String
    ): Output {

        val all = methods(code).filter { !it.isPrivate }.distinctBy { it.name }
        if (all.isEmpty()) throw IllegalStateException("No se encontraron métodos no privados en el código analizado.")
        val testable = if (focus.isNotBlank()) {
            all.filter { it.name.contains(focus, ignoreCase = true) }.ifEmpty { all }
        } else all

        val mapSizes = testable
            .filter { it.returnType.contains("Map[") }
            .mapNotNull { m -> mapSize(m)?.let { size -> m.name to size } }
            .toMap()

        val comp = findCompanions(basePath, sourceFile, fullSource, spec.sourceName)
        val preamble = ParquetProbe.companionPreamble(comp.imported)
        val companionsText = (comp.imported + comp.siblings)
            .joinToString("\n\n") { "// Archivo: ${it.name}\n" + it.readText().take(12000) }

        val (header, pattern) = TestPromptBuilder.headerAndPattern(testType, pkg, spec, configRel)
        val prompt = buildPrompt(
            testType, pkg, spec, header, pattern, code, companionsText,
            configText, infos, testable, mapSizes, preamble.isNotBlank()
        )
        val ctx = Ctx(testable, preamble, infos, basePath, askAi)

        progress("Consultando a la IA y midiendo con Spark...")
        val first = attempt(prompt, ctx)
        var best = first

        if (first.issues.isNotEmpty()) {
            progress("Corrigiendo problemas detectados (reintento)...")
            val retryPrompt = prompt + "\n\nTu respuesta anterior tuvo estos problemas:\n" +
                    first.issues.joinToString("\n") { "- $it" } +
                    "\n\nCorrígelos y devuelve la respuesta COMPLETA de nuevo (sondas + @@TEST)."
            try {
                val second = attempt(retryPrompt, ctx)
                if (second.issues.size <= first.issues.size) best = second
            } catch (ex: Exception) {
                // se conserva el primer intento
            }
        }

        if (best.parsed.test.isBlank()) {
            throw IllegalStateException("La IA no devolvió la clase de test.\n" + best.issues.joinToString("\n"))
        }

        val problems = ArrayList<String>(best.issues)
        var test = best.parsed.test
        test = Regex("""class\s+[A-Za-z0-9_]+(\s+extends)""").replaceFirst(test, "class ${spec.testClassName}$1")
        test = addWildcardImports(test, fullSource)
        test = substitute(test, best.res, mapSizes, problems)

        val usedKeys = PH.findAll(best.parsed.test)
            .filter { it.groupValues[1] != "MAPSIZE" }
            .map { it.groupValues[2] }
            .distinct()
            .toList()
        val measured = usedKeys.mapNotNull { k ->
            val m = best.res.measures[k] ?: return@mapNotNull null
            val code0 = best.parsed.probes.firstOrNull { it.key == k }?.code.orEmpty()
            val pqs = PQ_REF.findAll(code0).map { it.groupValues[1] }.distinct().joinToString(", ")
            "$k: ${m.rows} filas x ${m.cols} columnas" + if (pqs.isNotEmpty()) " (parquet: $pqs)" else ""
        }

        return Output(test, measured, problems.distinct())
    }
}