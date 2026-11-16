package com.example.pgat

import java.io.File

/**
 * Genera tests de GetData / Generate con números MEDIDOS en DuckDB.
 *  - Los métodos se piden a la IA en lotes pequeños (BATCH_SIZE). Si un lote falla o le faltan métodos,
 *    se reintenta método por método (runBatch).
 *  - Los inputs de cada método, las constantes (String y List[String]) y los valores del .conf se resuelven
 *    determinísticamente sin IA, y DuckDB pre-ejecuta las sondas SQL sobre los parquet reales.
 *  - Para Generate se incluye también la clase GetData que arma el Map de entrada.
 *  - Métodos compuestos (que terminan llamando a otro método de la clase, p. ej. apply -> joinFinal):
 *    no se piden a la IA. Su test usa los mismos números que el último paso, así que coinciden por construcción.
 *  - Si el resultado no está completo, GenerateTestAction lo guarda como .draft.
 */
object MeasuredTestBuilder {

    private const val MAX_TRIES = 3
    private const val BATCH_SIZE = 3

    data class Output(
        val code: String,
        val measured: List<String>,
        val problems: List<String>,
        val complete: Boolean
    )

    data class Method(
        val name: String,
        val params: String,
        val returnType: String,
        val isPrivate: Boolean,
        val body: String
    )

    data class InputRef(val constName: String, val table: String, val parquet: String?)

    private data class Parsed(val probes: List<ParquetProbe.Probe>, val test: String)
    private data class Attempt(
        val parsed: Parsed,
        val res: ParquetProbe.RunResult,
        val issues: List<String>,
        val notes: List<String>
    )
    private data class Verified(val res: ParquetProbe.RunResult, val issues: List<String>, val notes: List<String>)
    private data class Companions(
        val imported: List<File>,
        val siblings: List<File>,
        val all: List<File>,
        val getData: List<File>
    )
    private data class ImportRef(val name: String, val pkg: String)
    private data class BatchResult(
        val test: String,
        val complete: Boolean,
        val problems: List<String>,
        val measured: List<String>
    )

    private class Ctx(
        val testable: List<Method>,
        val infos: List<ParquetProbe.Info>,
        val askAi: (String) -> String,
        val sites: Map<String, List<CallSiteResolver.Site>>,
        val preMeasured: Map<String, ParquetProbe.Measure>,
        val preProbes: Map<String, ParquetProbe.Probe>
    )

    private class Job(
        val testType: String,
        val pkg: String,
        val spec: TestPromptBuilder.TestSpec,
        val header: String,
        val pattern: String,
        val code: String,
        val companionsText: String,
        val configText: String,
        val infos: List<ParquetProbe.Info>,
        val mapSizes: Map<String, Int>,
        val sites: Map<String, List<CallSiteResolver.Site>>,
        val preMeasured: Map<String, ParquetProbe.Measure>,
        val preProbes: Map<String, ParquetProbe.Probe>,
        val resolvedConstsText: String,
        val inputs: Map<String, List<InputRef>>,
        val composites: Map<String, String>,
        val fullSource: String,
        val sourceFile: File,
        val basePath: File,
        val askAi: (String) -> String
    )

    private val PH = Regex("""\{\{(ROWS|COLS|MAPSIZE):([A-Za-z0-9_]+)\}\}""")
    private val LEFTOVER = Regex("""\{\{[A-Za-z_]+(:[A-Za-z0-9_]*)?\}\}""")
    private val HARDCODED = Regex("""(count\(\)|\.columns\.length|\.size|\.length)\s*(==|shouldBe|should\s+be)\s*\d+""")
    private val FROM_REGEX = Regex(
        """(?i)\b(?:from|join)\s+((?:\x22?[A-Za-z_][A-Za-z0-9_]*\x22?\s*\.\s*)*\x22?[A-Za-z_][A-Za-z0-9_]*\x22?)"""
    )
    private val READ_PQ = Regex("""read\s*\.\s*parquet\s*\(\s*\x22([^\x22]+)\x22""")
    private val BLOCK_START = Regex("""(?m)^[ \t]*\x22[^\x22\n]*\x22\s+should\s+\x22""")
    private val BLOCK_NUMBER = Regex("""(?m)^([ \t]*\x22)(?:\d+|\{\{N\}\})\.""")
    private val INPUT_REGEX = Regex("""inputsDf\(\s*(?:[A-Za-z0-9_]+\s*\.\s*)*([A-Z][A-Z0-9_]*)\s*\)""")

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

    /** Cantidad exacta de entradas del Map(k -> v, ...) literal que arma el método. */
    private fun mapSize(m: Method): Int? {
        val found = Regex("""\bMap\s*(\[[^\]]*\])?\s*\(""").find(m.body) ?: return null
        val inner = CallSiteResolver.balancedInner(m.body, found.range.last) ?: return null
        if (!inner.contains("->")) return null
        return ParametryAssertGenerator.countTopLevelElements(inner)
    }

    /**
     * Para cada método, los inputs que usa (inputsDf(CONSTANTE)) con su tabla y su parquet,
     * todo resuelto sin IA a partir del valor de la constante y del .conf.
     */
    private fun resolveInputs(
        methods: List<Method>,
        constants: Map<String, String>,
        conf: String,
        infos: List<ParquetProbe.Info>
    ): Map<String, List<InputRef>> {
        val cache = HashMap<String, InputRef>()
        fun ref(n: String): InputRef = cache.getOrPut(n) {
            val value = constants[n].orEmpty()
            val hit = CallSiteResolver.tablesFor(n, value, conf, infos)
            InputRef(n, value, hit.firstOrNull())
        }
        return methods.associate { m ->
            m.name to INPUT_REGEX.findAll(m.body)
                .map { ref(it.groupValues[1]) }
                .distinctBy { it.constName }
                .toList()
        }
    }

    /**
     * Método compuesto: llama a otros métodos de la clase y su ÚLTIMA llamada es el último paso.
     * Devuelve compuesto -> último paso. Si el último paso también es compuesto, se omite (no se encadena).
     */
    private fun detectComposites(methods: List<Method>): Map<String, String> {
        val names = methods.map { it.name }.toSet()
        val found = LinkedHashMap<String, String>()
        for (m in methods) {
            val last = Regex("""\b(\w+)\s*\(""").findAll(m.body)
                .map { it.groupValues[1] }
                .filter { it != m.name && it in names }
                .lastOrNull() ?: continue
            found[m.name] = last
        }
        return found.filterValues { it !in found.keys }
    }

    private fun importRefs(fullSource: String, selfName: String): List<ImportRef> {
        val out = ArrayList<ImportRef>()
        val isName = { s: String -> s.isNotEmpty() && s[0].isUpperCase() && s.all { it.isLetterOrDigit() || it == '_' } }
        for (raw in fullSource.lines().map { it.trim() }.filter { it.startsWith("import ") }) {
            val body = raw.removePrefix("import ").trim()
            if (body.contains("{")) {
                val pkg = body.substringBefore("{").trimEnd('.')
                body.substringAfter("{").substringBefore("}").split(',').forEach { part ->
                    val n = part.substringBefore("=>").trim()
                    if (isName(n)) out.add(ImportRef(n, pkg.replace('.', '/')))
                }
            } else {
                val cleanBody = body.removeSuffix("._")
                val segs = cleanBody.split('.')
                val idx = segs.indexOfLast { isName(it) }
                if (idx >= 0) out.add(ImportRef(segs[idx], segs.take(idx).joinToString("/")))
            }
        }
        return out.distinctBy { it.name }.filter { it.name != selfName }
    }

    private fun findGetData(index: Map<String, List<File>>, selfName: String): List<File> {
        val core = selfName.substringAfter("Generate", selfName).removePrefix("Data")
        if (core.isNotEmpty()) {
            val exact = index["GetData$core"].orEmpty()
            if (exact.isNotEmpty()) return exact.take(1)
            val byCore = index.filterKeys { it.startsWith("GetData") && it.contains(core) }
                .values.flatten()
            if (byCore.isNotEmpty()) return byCore.take(1)
        }
        val mapDef = Regex("""def\s+\w+[^=\n]*Map\[\s*String\s*,\s*DataFrame\s*\]""")
        return index.values.flatten()
            .filter { f ->
                f.nameWithoutExtension != selfName &&
                        !f.path.replace("\\", "/").contains("/src/test/") &&
                        try { mapDef.containsMatchIn(f.readText()) } catch (_: Exception) { false }
            }
            .take(1)
    }

    private fun findCompanions(basePath: File, sourceFile: File, fullSource: String, selfName: String): Companions {
        val refs = importRefs(fullSource, selfName)
        val usedConstants = CallSiteResolver.extractConstantTokens(fullSource)

        val skip = setOf("target", ".git", ".idea", "node_modules", "build", "out")
        val root = LongPaths.resolve(basePath, true)
        val index = HashMap<String, MutableList<File>>()
        try {
            root.walkTopDown().onEnter { it == root || it.name !in skip }.forEach { f ->
                if (f.isFile && f.extension == "scala") {
                    index.getOrPut(f.nameWithoutExtension) { ArrayList() }.add(f)
                }
            }
        } catch (ex: Exception) {
        }

        fun pick(ref: ImportRef): File? {
            val c = (index[ref.name] ?: return null).sortedBy { it.path }
            return c.firstOrNull {
                ref.pkg.isNotEmpty() && it.path.replace("\\", "/").contains("${ref.pkg}/${ref.name}.scala")
            } ?: c.firstOrNull { !it.path.replace("\\", "/").contains("/src/test/") }
            ?: c.first()
        }

        val siblingDir = sourceFile.parentFile?.let { LongPaths.resolve(it, true) }
        val sameFolderFiles = (siblingDir?.listFiles() ?: emptyArray<File>())
            .filter { it.isFile && it.extension == "scala" && it.name != sourceFile.name }
            .sortedBy { it.name }

        val constantProviderFiles = index.values.flatten()
            .filter { f ->
                f.name != sourceFile.name &&
                        !f.path.replace("\\", "/").contains("/src/test/") &&
                        try {
                            val vals = ParametryAssertGenerator.extractVals(f.readText())
                            vals.isNotEmpty() && (usedConstants.isEmpty() || vals.any { it.name in usedConstants })
                        } catch (_: Exception) {
                            false
                        }
            }
            .distinct()

        val all = (refs.mapNotNull { pick(it) } + constantProviderFiles + sameFolderFiles).distinct()
        val imported = (refs.mapNotNull { pick(it) } + constantProviderFiles).distinct().take(4)
        val getData = findGetData(index, selfName)

        val mapDef = Regex("""def\s+\w+[^=\n]*Map\[\s*String\s*,\s*DataFrame\s*\]""")
        val siblings = sameFolderFiles
            .filter { f ->
                imported.none { it.name == f.name } &&
                        getData.none { it.name == f.name } &&
                        try { mapDef.containsMatchIn(f.readText()) } catch (_: Exception) { false }
            }
            .take(2)

        return Companions(imported, siblings, all, getData)
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    private fun buildResolvedConstsSection(
        fullSource: String,
        constants: Map<String, String>,
        listConsts: Map<String, List<String>>,
        conf: String
    ): String {
        val used = CallSiteResolver.extractConstantTokens(fullSource)
        if (used.isEmpty()) return ""
        val lines = ArrayList<String>()
        lines.add("CONSTANTES Y VALORES DE .CONF YA RESUELTOS DETERMINÍSTICAMENTE POR EL PLUGIN (úsalo tal cual en tus sondas SQL):")
        for (c in used) {
            val list = listConsts[c]
            val str = constants[c]
            when {
                list != null -> lines.add("- $c (List[${list.size} columnas]) = ${list.joinToString(", ") { "\"$it\"" }}")
                str != null -> {
                    val confVal = CallSiteResolver.resolveConfScalar(str, conf)
                    val extra = if (confVal != null && confVal != str) "  -> valor en .conf: \"$confVal\"" else ""
                    lines.add("- $c = \"$str\"$extra")
                }
            }
        }
        return if (lines.size > 1) lines.joinToString("\n") else ""
    }

    private fun siteSection(
        sites: Map<String, List<CallSiteResolver.Site>>,
        preProbes: Map<String, ParquetProbe.Probe>,
        preMeasured: Map<String, ParquetProbe.Measure>
    ): String {
        if (sites.isEmpty() && preProbes.isEmpty()) return ""
        val lines = ArrayList<String>()
        lines.add("LLAMADAS REALES, PARQUET Y SONDAS DUCKDB PRE-CALCULADAS POR EL PLUGIN (ES OBLIGATORIO USARLAS TAL CUAL):")
        for ((method, list) in sites) {
            list.forEachIndexed { i, s ->
                val t = if (s.tables.isEmpty()) {
                    "(elige la tabla por nombre y columnas)"
                } else {
                    "tabla SQL: " + s.tables.joinToString(", ")
                }
                val colsInfo = if (s.columns.isNotEmpty()) {
                    " | SELECT exacto (${s.columns.size} cols): ${s.columns.joinToString(", ")}"
                } else ""
                lines.add("- ${if (i == 0) "[USAR ESTA] " else ""}${s.call}  ->  $t$colsInfo")
            }
            val pre = preProbes[method]
            val meas = preMeasured[method]
            if (pre != null && meas != null) {
                lines.add("  [SONDA DUCKDB YA VERIFICADA PARA $method (${meas.rows} filas, ${meas.cols} cols) — cópiala tal cual bajo @@PROBE $method]:")
                lines.add("  @@PROBE $method")
                lines.add("  ${pre.code}")
            }
        }
        lines.add("")
        lines.add("Reglas obligatorias de esta sección:")
        lines.add("a) En @@TEST llama a cada método exactamente con la llamada marcada [USAR ESTA] (mismos nombres de constantes, SIN anteponer el nombre del object de constantes porque ya está importado con ._).")
        lines.add("b) Si el método tiene una [SONDA DUCKDB YA VERIFICADA], copia exactamente ese @@PROBE <metodo> y usa {{ROWS:<metodo>}} y {{COLS:<metodo>}} en el test.")
        lines.add("c) Para cualquier otro método sin sonda pre-calculada, escribe la sonda SQL seleccionando EXACTAMENTE las columnas de la constante List[String] y leyendo la tabla indicada.")
        return lines.joinToString("\n")
    }

    private fun inputsSection(inputs: Map<String, List<InputRef>>): String {
        val rows = inputs.flatMap { (method, refs) ->
            refs.map { r ->
                val where = r.parquet?.let { "parquet: $it" } ?: "SIN PARQUET (no escribas sonda que dependa de él)"
                "- $method usa ${r.constName} = \"${r.table}\"  ->  $where"
            }
        }
        if (rows.isEmpty()) return ""
        return (listOf("INPUTS QUE USA CADA MÉTODO (resueltos por el plugin desde las constantes y el .conf; es OBLIGATORIO respetarlos):") +
                rows +
                listOf("Las claves del Map de entrada son las que devuelve la clase GetData (archivo marcado GetData)."))
            .joinToString("\n")
    }

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
        resolvedConstsText: String,
        siteText: String,
        inputsText: String
    ): String {

        val parquetLines = infos.joinToString("\n") { i ->
            val pairs = i.columns.mapIndexed { idx, c ->
                val t = i.colTypes.getOrNull(idx)
                if (t.isNullOrBlank()) c else "$c $t"
            }
            val cols = if (pairs.size > 60) {
                pairs.take(60).joinToString(", ") + ", ... (${pairs.size} en total)"
            } else {
                pairs.joinToString(", ")
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

        val pickRule = if (siteText.isNotBlank() || inputsText.isNotBlank()) {
            "- El parquet de cada método está FIJADO en las secciones LLAMADAS REALES e INPUTS: úsalo tal cual. " +
                    "Solo si un método no aparece ahí, elige el parquet por nombre y por columnas."
        } else {
            "- Elige el parquet que corresponde a la constante o parámetro con que se llama al método (por nombre y por columnas)."
        }

        return listOf(
            "Eres un experto en Scala, Apache Spark, SQL (DuckDB) y ScalaTest. NO HABLES. NO EXPLIQUES. Sin bloques markdown.",
            "",
            "TIPO DE TEST: $testType",
            "",
            "CÓDIGO A PROBAR (clase ${spec.sourceName}, package del test: $pkg):",
            code,
            "",
            "ARCHIVOS ASOCIADOS (constantes, clases relacionadas y GetData):",
            companionsText.ifBlank { "(no se encontraron)" },
            "",
            "ARCHIVO .conf DEL TEST (úsalo solo para conocer valores de config como año o mes):",
            configText.take(8000).ifBlank { "(vacío)" },
            "",
            resolvedConstsText,
            "",
            "PARQUET DE MUESTRA, YA LEÍDOS EN DUCKDB (cada uno es una tabla SQL con ese nombre; filas y columnas reales):",
            parquetLines,
            "",
            "MÉTODOS QUE DEBES CUBRIR EN ESTA RESPUESTA (todos, ninguno se puede omitir):",
            methodLines,
            "",
            inputsText,
            "",
            siteText,
            "",
            "TU RESPUESTA TIENE DOS PARTES.",
            "",
            "PARTE 1 - SONDAS. Por cada resultado (DataFrame) cuyas filas/columnas haya que medir escribe:",
            "@@PROBE <clave>",
            "<UNA sola consulta SQL (DuckDB) que reproduce lo que hace el método y devuelve el mismo resultado>",
            "",
            "Reglas de las sondas:",
            "- Es SQL, no Scala. Una sola sentencia SELECT (o WITH ... SELECT), sin punto y coma final.",
            "- La clave de cada sonda es exactamente el nombre del método (ej: @@PROBE getDefaultMasterTable).",
            "- Si arriba se te dio una [SONDA DUCKDB YA VERIFICADA] para el método, cópiala EXACTAMENTE igual.",
            "- Las tablas disponibles son EXACTAMENTE los nombres de la lista de parquet de arriba: escríbelos en el FROM",
            "  (por ejemplo: FROM nombre_del_parquet). No uses rutas, read_parquet, esquemas ni ReaderWithDataproc.",
            "- No inventes tablas. Si un input de la sección INPUTS dice SIN PARQUET, no escribas sonda para ese método.",
            pickRule,
            "  Si el método usa varios inputs, haz el JOIN o la consulta entre varias tablas.",
            "- Copia la lógica del método tal cual (mismos select, trim, filtros, joins, fechas, agregaciones).",
            "- Usa las columnas exactas de la sección CONSTANTES Y VALORES DE .CONF YA RESUELTOS. Nunca uses SELECT * si el método selecciona una lista de columnas.",
            "- Equivalencias Spark -> DuckDB: trim(col) -> trim(CAST(col AS VARCHAR)); lpad(col, n, '0') -> lpad(CAST(col AS VARCHAR), n, '0');",
            "  concat(a, b) -> concat(CAST(a AS VARCHAR), CAST(b AS VARCHAR)); col.as(x) -> col AS x; lit('x') -> 'x'.",
            "- DuckDB NO convierte tipos automáticamente (Spark sí). En COALESCE, CASE WHEN, UNION, IN y comparaciones, los dos lados",
            "  deben tener el MISMO tipo. Si mezclas columnas de distinto tipo o una columna con un literal, castea TODO de forma explícita a VARCHAR o DATE.",
            "- Filtrar por la partición máxima se escribe con subconsulta, por ejemplo:",
            "  SELECT <columnas> FROM <tabla> WHERE <expresión_partición> = (SELECT max(<expresión_partición>) FROM <tabla>)",
            "  y si además hay un corte (<= valor), agrégalo en el WHERE de la subconsulta.",
            "- La clave es única (letras, números y _).",
            "",
            "PARTE 2 - TEST:",
            "@@TEST",
            "<la clase de test completa>",
            "",
            "Reglas del test:",
            "1. Usa EXACTAMENTE este header (ya tiene el package de destino, imports del proyecto, clase y config correctos; no los cambies ni los borres):",
            "",
            header,
            "",
            "2. Sigue este patrón para el contenido de los tests:",
            "",
            pattern,
            "",
            "3. PROHIBIDO escribir a mano cantidades de filas, columnas o elementos. Usa SOLO los placeholders",
            "   {{ROWS:clave}}, {{COLS:clave}} (claves de tus sondas) y {{MAPSIZE:metodo}}; el sistema los reemplaza por los",
            "   valores medidos en DuckDB. Escribe {{N}} como número de cada test; el sistema numera.",
            "4. Un test por cada método de la lista. No inventes métodos, constantes ni rutas.",
            "5. Sin TODO ni comentarios de relleno.",
            "6. Si el método tiene llamadas reales listadas arriba, el test usa EXACTAMENTE la llamada marcada [USAR ESTA] con los nombres de las constantes directos (sin anteponer el nombre del object de constantes, pues ya está importado con ._).",
            "7. Termina la respuesta con la llave que cierra la clase. No cortes la respuesta a la mitad.",
            "8. Para leer un parquet usa la ruta relativa EXACTA de la lista PARQUET DE MUESTRA, con el nombre completo de la carpeta (incluido el sufijo partition_...). Nunca la acortes.",
            "9. En las sondas SQL, nunca uses como tabla el nombre de otro método ni sufijos como _src. Escribe la consulta completa desde los parquet de la lista.",
            "10. Antes de usar una columna en un SELECT, JOIN o WHERE, verifica que exista en la lista de columnas (con su tipo) del parquet correspondiente."
        ).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Respuesta de la IA
    // ------------------------------------------------------------------

    private fun normalizeTestCalls(test: String, sites: Map<String, List<CallSiteResolver.Site>>): String {
        var out = CallSiteResolver.stripConstQualifiers(test)
        if (sites.isEmpty()) return out

        for ((method, methodSites) in sites) {
            if (methodSites.isEmpty()) continue
            val callRegex = Regex("""(\bgetObjectProcess\s*\.\s*${Regex.escape(method)}\s*)\(""")
            val matches = callRegex.findAll(out).toList().asReversed()
            for (m in matches) {
                val openParen = m.range.last
                val inner = CallSiteResolver.balancedInner(out, openParen) ?: continue
                val normInner = CallSiteResolver.normArgs(inner)
                val matchedSite = methodSites.firstOrNull { it.normArgs == normInner }
                if (matchedSite != null) {
                    out = out.substring(0, openParen + 1) + matchedSite.args + out.substring(openParen + 1 + inner.length)
                } else {
                    val primary = methodSites.first()
                    out = out.substring(0, openParen + 1) + primary.args + out.substring(openParen + 1 + inner.length)
                }
            }
        }
        return out
    }

    private fun parse(resp: String, sites: Map<String, List<CallSiteResolver.Site>> = emptyMap()): Parsed {
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
        val normalizedTest = normalizeTestCalls(test.toString().trim(), sites)
        return Parsed(probes.distinctBy { it.key }, normalizedTest)
    }

    private fun balanced(src: String): Boolean {
        if (!Regex("""class\s+\w+\s+extends""").containsMatchIn(src)) return false
        var depth = 0
        var inStr = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
            i++
        }
        return depth == 0
    }

    private fun staticIssues(p: Parsed, testable: List<Method>, preMeasured: Map<String, ParquetProbe.Measure>): List<String> {
        val out = ArrayList<String>()
        if (!Regex("""class\s+\w+\s+extends""").containsMatchIn(p.test)) {
            out.add("La parte @@TEST no contiene la clase de test completa.")
            return out
        }
        if (!balanced(p.test)) {
            out.add("La clase de test quedó incompleta (llaves o paréntesis sin cerrar). Escríbela completa.")
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
        val keys = p.probes.map { it.key }.toSet() + preMeasured.keys
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

    // ------------------------------------------------------------------
    // Verificación semántica sobre lo que midió DuckDB
    // ------------------------------------------------------------------

    private fun tablesOf(sql: String, infos: List<ParquetProbe.Info>): Set<String> {
        val byLower = infos.associateBy { it.name.lowercase() }
        return FROM_REGEX.findAll(sql)
            .mapNotNull { m ->
                val last = m.groupValues[1].split('.').last().trim().trim('"')
                byLower[last.lowercase()]?.name
            }
            .toCollection(LinkedHashSet())
    }

    private fun testBlocks(test: String): List<String> {
        val starts = BLOCK_START.findAll(test).map { it.range.first }.toList()
        if (starts.isEmpty()) return listOf(test)
        return starts.mapIndexed { i, s ->
            test.substring(s, if (i + 1 < starts.size) starts[i + 1] else test.length)
        }
    }

    private fun verifySites(p: Parsed, raw: ParquetProbe.RunResult, ctx: Ctx): Verified {
        val issues = ArrayList<String>()
        val notes = ArrayList<String>()
        val measures = LinkedHashMap(raw.measures)
        val errors = LinkedHashMap(raw.errors)

        for ((k, m) in ctx.preMeasured) {
            measures.putIfAbsent(k, m)
        }
        val probeByKey = (ctx.preProbes.values + p.probes).associateBy { it.key }

        // 1) Rutas de parquet inventadas en el test
        val known = ctx.infos.map { it.relPath.replace("\\", "/").trimEnd('/') }
        READ_PQ.findAll(p.test).forEach { m ->
            val path = m.groupValues[1].replace("\\", "/").trimEnd('/')
            val ok = known.any { path == it || path.endsWith("/$it") || it.endsWith("/$path") || path.startsWith("$it/") }
            if (!ok) issues.add("El test lee la ruta \"$path\" que no está entre los parquet de muestra.")
        }

        // 2) Llamada del test == llamada real del código
        if (ctx.sites.isNotEmpty()) {
            for (block in testBlocks(p.test)) {
                val blockKeys = PH.findAll(block)
                    .filter { it.groupValues[1] != "MAPSIZE" }
                    .map { it.groupValues[2] }
                    .distinct()
                    .toList()
                if (blockKeys.isEmpty()) continue

                for ((method, sites) in ctx.sites) {
                    for (m in Regex("""\b${Regex.escape(method)}\s*\(""").findAll(block)) {
                        val inner = CallSiteResolver.balancedInner(block, m.range.last) ?: continue
                        if (!CallSiteResolver.hasConstants(inner)) continue

                        val site = sites.firstOrNull { it.normArgs == CallSiteResolver.normArgs(inner) }
                        if (site == null) {
                            issues.add(
                                "La llamada $method(${inner.trim()}) del test NO existe en el código fuente. " +
                                        "Las llamadas reales son: " + sites.joinToString(" | ") { it.call }
                            )
                            blockKeys.forEach { k ->
                                measures.remove(k)
                                errors[k] = "la llamada del test no coincide con ninguna llamada real de $method"
                            }
                            continue
                        }

                        val detMeasure = ctx.preMeasured[method]
                        if (detMeasure != null) {
                            blockKeys.forEach { k ->
                                measures[k] = detMeasure
                                errors.remove(k)
                            }
                            continue
                        }

                        if (site.tables.isEmpty()) {
                            val anyValidTable = blockKeys.any { k ->
                                val probe = probeByKey[k]
                                probe != null && tablesOf(probe.code, ctx.infos).isNotEmpty()
                            }
                            if (!anyValidTable) {
                                notes.add("No se pudo verificar contra qué parquet lee ${site.call}; revísalo a mano.")
                            }
                            continue
                        }

                        for (k in blockKeys) {
                            val probe = probeByKey[k] ?: continue
                            val used = tablesOf(probe.code, ctx.infos)
                            if (used.isEmpty() || !site.tables.containsAll(used)) {
                                measures.remove(k)
                                errors[k] = "lee [${used.joinToString(", ")}] pero ${site.call} lee " +
                                        "[${site.tables.joinToString(", ")}]; la sonda debe usar solo esa(s) tabla(s)"
                            }
                        }
                    }
                }
            }
        }

        return Verified(ParquetProbe.RunResult(measures, errors), issues.distinct(), notes.distinct())
    }

    private fun attempt(prompt: String, ctx: Ctx): Attempt {
        val parsed = parse(ctx.askAi(prompt), ctx.sites)
        val issues = ArrayList(staticIssues(parsed, ctx.testable, ctx.preMeasured))
        val raw = ParquetProbe.runProbes(parsed.probes, ctx.infos)
        val v = verifySites(parsed, raw, ctx)
        issues.addAll(v.issues)
        val res = v.res

        val probeKeys = parsed.probes.map { it.key }.toSet() + ctx.preMeasured.keys
        val used = PH.findAll(parsed.test)
            .filter { it.groupValues[1] != "MAPSIZE" }
            .map { it.groupValues[2] }
            .toSet()
        used.filter { it in probeKeys && it !in res.measures }.forEach {
            issues.add("La sonda '$it' falló: ${res.errors[it] ?: "sin resultado"}")
        }
        return Attempt(parsed, res, issues.distinct(), v.notes)
    }

    // ------------------------------------------------------------------
    // Compuestos: el test de apply usa los números de su último paso
    // ------------------------------------------------------------------

    private fun blockTitleHas(block: String, method: String): Boolean =
        Regex("\"[^\"\\n]*\\b" + Regex.escape(method) + "\\b[^\"\\n]*\"\\s+should").containsMatchIn(block)

    private fun injectComposites(raw: String, comps: Map<String, String>, sourceName: String): String {
        var out = raw
        for ((c, l) in comps) {
            val block = testBlocks(out).firstOrNull { blockTitleHas(it, l) } ?: continue
            val key = PH.findAll(block).firstOrNull { it.groupValues[1] == "ROWS" }?.groupValues?.get(2) ?: l
            val receiver = Regex("""val\s+evaluate\s*=\s*(new\s+[^\n]*?)\s*\.\s*${Regex.escape(l)}\s*\(""")
                .find(block)?.groupValues?.get(1)?.trim() ?: continue
            val vars = Regex("""\w+""").findAll(receiver).map { it.value }.toSet()
            val inputLine = Regex("""(?m)^[ \t]*val\s+(\w+)\s*:\s*Map\[String,\s*DataFrame\]\s*=[^\n]*""")
                .findAll(out).firstOrNull { it.groupValues[1] in vars }?.value?.trim()
            val idx = out.trimEnd().lastIndexOf('}')
            if (idx < 0) continue

            val body = buildString {
                append("  \"{{N}}. $sourceName.$c\" should \"get a dataframe with {{ROWS:$key}} rows and {{COLS:$key}} columns (igual que $l)\" in {\n")
                if (inputLine != null) append("    $inputLine\n")
                append("    val evaluate = $receiver.$c\n")
                append("    assert(evaluate.isInstanceOf[DataFrame] &&\n")
                append("      evaluate.columns.length == {{COLS:$key}} &&\n")
                append("      evaluate.count() == {{ROWS:$key}}\n")
                append("    )\n")
                append("  }")
            }
            out = out.substring(0, idx).trimEnd() + "\n\n" + body + "\n}\n"
        }
        return out
    }

    // ------------------------------------------------------------------
    // Lotes
    // ------------------------------------------------------------------

    private fun generateBatch(job: Job, batch: List<Method>): BatchResult {
        val names = batch.map { it.name }.toSet()
        val sites = job.sites.filterKeys { it in names }
        val preProbes = job.preProbes.filterKeys { it in names }
        val preMeasured = job.preMeasured.filterKeys { it in names }
        val inputs = job.inputs.filterKeys { it in names }
        val mapSizes = job.mapSizes.filterKeys { it in names }

        val prompt = buildPrompt(
            job.testType, job.pkg, job.spec, job.header, job.pattern, job.code,
            job.companionsText, job.configText, job.infos, batch, mapSizes,
            job.resolvedConstsText,
            siteSection(sites, preProbes, preMeasured),
            inputsSection(inputs)
        )
        val ctx = Ctx(batch, job.infos, job.askAi, sites, preMeasured, preProbes)

        var best = attempt(prompt, ctx)
        var tries = 1

        while (best.issues.isNotEmpty() && tries < MAX_TRIES) {
            val failedSql = best.parsed.probes
                .filter { it.key in best.res.errors.keys }
                .joinToString("\n\n") { p ->
                    "@@PROBE ${p.key}\n${p.code}\nERROR DE DUCKDB: ${best.res.errors[p.key]}"
                }
            val retryPrompt = prompt + "\n\nTu respuesta anterior tuvo estos problemas:\n" +
                    best.issues.joinToString("\n") { "- $it" } +
                    (if (failedSql.isNotBlank())
                        "\n\nSONDAS QUE FALLARON (corrige solo lo que indica el error):\n$failedSql"
                    else "") +
                    "\n\nCorrígelos y devuelve la respuesta COMPLETA de nuevo (sondas + @@TEST). " +
                    "Recuerda: las llamadas, tablas e INPUTS de las secciones obligatorias son obligatorios."
            try {
                val next = attempt(retryPrompt, ctx)
                if (next.issues.size <= best.issues.size) best = next
            } catch (ex: Exception) {
                break
            }
            tries++
        }

        val lote = batch.joinToString(", ") { it.name }
        if (best.parsed.test.isBlank() || !balanced(best.parsed.test)) {
            return BatchResult(
                "", false,
                listOf("Lote [$lote]: la IA no devolvió una clase completa."),
                emptyList()
            )
        }

        val omitted = batch.filter { m ->
            !Regex("""\b${Regex.escape(m.name)}\b""").containsMatchIn(best.parsed.test)
        }
        if (omitted.isNotEmpty()) {
            return BatchResult(
                "", false,
                listOf("Lote [$lote]: la IA omitió ${omitted.joinToString(", ") { it.name }}."),
                emptyList()
            )
        }

        val problems = ArrayList<String>(best.issues)
        problems.addAll(best.notes)

        val raw = injectComposites(
            best.parsed.test,
            job.composites.filterValues { it in names },
            job.spec.sourceName
        )

        var test = Regex("""class\s+[A-Za-z0-9_]+(\s+extends)""")
            .replaceFirst(raw, "class ${job.spec.testClassName}$1")
        test = substitute(test, best.res, mapSizes, problems)

        val usedKeys = PH.findAll(raw)
            .filter { it.groupValues[1] != "MAPSIZE" }
            .map { it.groupValues[2] }
            .distinct()
            .toList()
        val measured = usedKeys.mapNotNull { k ->
            val m = best.res.measures[k] ?: return@mapNotNull null
            val sql = (best.parsed.probes.firstOrNull { it.key == k } ?: preProbes[k]
            ?: preProbes.values.firstOrNull { k.startsWith(it.key) })?.code.orEmpty()
            val pqs = tablesOf(sql, job.infos).joinToString(", ")
            "$k: ${m.rows} filas x ${m.cols} columnas" + if (pqs.isNotEmpty()) " (parquet: $pqs)" else ""
        }

        return BatchResult(test, true, problems, measured)
    }

    private fun runBatch(job: Job, batch: List<Method>): List<BatchResult> {
        val first = try {
            generateBatch(job, batch)
        } catch (ex: Exception) {
            BatchResult(
                "", false,
                listOf("Lote [${batch.joinToString(", ") { it.name }}]: ${ex.message.orEmpty()}"),
                emptyList()
            )
        }
        if (first.complete || batch.size == 1) return listOf(first)

        return batch.map { m ->
            try {
                generateBatch(job, listOf(m))
            } catch (ex: Exception) {
                BatchResult(
                    "", false,
                    listOf("Método ${m.name}: ${ex.message.orEmpty()}"),
                    emptyList()
                )
            }
        }
    }

    private fun splitParts(test: String): Pair<String, List<String>> {
        val starts = BLOCK_START.findAll(test).map { it.range.first }.toList()
        if (starts.isEmpty()) return test to emptyList()
        val header = test.substring(0, starts.first())
        val blocks = starts.mapIndexed { i, s ->
            test.substring(s, if (i + 1 < starts.size) starts[i + 1] else test.length).trimEnd()
        }
        return header to blocks
    }

    private fun mergeParts(parts: List<String>, templateHeader: String): String {
        if (parts.isEmpty()) return ""
        val baseHeader = splitParts(templateHeader).first
            .replace("// AQUÍ VAN LOS TESTS: uno por cada método de la lista.", "")
            .replace("// AQUÍ VAN LOS TESTS: uno para el método principal (normalmente \"apply\")", "")
            .replace("// y uno por cada método de transformación de la lista.", "")
            .trimEnd()
            .removeSuffix("}")
            .trimEnd()

        val firstPartHeader = splitParts(parts.first()).first.trimEnd()
        val headerToUse = if (Regex("""class\s+\w+\s+extends""").containsMatchIn(baseHeader)) {
            baseHeader
        } else {
            firstPartHeader
        }

        val blocks = ArrayList<String>()
        for (p in parts) {
            val (_, bs) = splitParts(p)
            val last = bs.size - 1
            bs.forEachIndexed { i, b ->
                blocks.add(if (i == last) b.removeSuffix("}").trimEnd() else b)
            }
        }
        return headerToUse + "\n\n" + blocks.joinToString("\n\n") + "\n}\n"
    }

    private fun renumber(test: String): String {
        var n = 0
        var out = BLOCK_NUMBER.replace(test) { m ->
            n++
            "${m.groupValues[1]}$n."
        }
        out = Regex("""\{\{N\}\}""").replace(out) {
            n++
            n.toString()
        }
        return out
    }

    // ------------------------------------------------------------------
    // Armado final
    // ------------------------------------------------------------------

    private fun insideString(text: String, pos: Int): Boolean {
        val ls = text.lastIndexOf('\n', pos - 1) + 1
        var inStr = false
        var i = ls
        while (i < pos) {
            val c = text[i]
            if (c == '\\' && inStr) i++ else if (c == '"') inStr = !inStr
            i++
        }
        return inStr
    }

    private fun substitute(
        test: String,
        res: ParquetProbe.RunResult,
        mapSizes: Map<String, Int>,
        problems: MutableList<String>
    ): String {
        return PH.replace(test) { m ->
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
                if (insideString(test, m.range.first)) "NO MEDIDO" else "-1 /* NO MEDIDO: $key */"
            }
        }
    }

    /**
     * Fuerza que la declaración `package ...` del archivo generado sea SIEMPRE el paquete de la ruta de destino
     * (`targetPkg`) y que estén todos los imports del proyecto (ContextProvider, clase bajo prueba si está en
     * un paquete distinto a `targetPkg`, y wildcards `._`).
     */
    private fun ensureProjectImports(
        testType: String,
        test: String,
        targetPkg: String,
        spec: TestPromptBuilder.TestSpec,
        fullSource: String,
        basePath: File,
        sourceFile: File
    ): String {
        val sourcePkg = TestPromptBuilder.extractPackage(fullSource).ifEmpty {
            TestPromptBuilder.packageFromOutputPath(sourceFile)
        }
        val cpImport = TestPromptBuilder.resolveContextProviderImport(targetPkg, sourcePkg, basePath, sourceFile)
        val sourceClassImport = if (sourcePkg.isNotEmpty() && sourcePkg != targetPkg) {
            "import $sourcePkg.${spec.sourceName}"
        } else {
            ""
        }
        val extraImports = TestPromptBuilder.resolveExtraImports(testType, targetPkg, fullSource, spec, basePath, sourceFile)

        // 1) Forzar que la línea `package ...` sea exactamente `package $targetPkg`
        var cleaned = if (targetPkg.isNotEmpty()) {
            if (Regex("""(?m)^\s*package\s+[A-Za-z0-9_.]+""").containsMatchIn(test)) {
                Regex("""(?m)^\s*package\s+[A-Za-z0-9_.]+""").replaceFirst(test, "package $targetPkg")
            } else {
                "package $targetPkg\n\n$test"
            }
        } else {
            test
        }

        // 2) Reemplazar cualquier import de ContextProvider incorrecto por el real del proyecto
        if (cpImport.isNotEmpty()) {
            cleaned = Regex("""(?m)^\s*import\s+[A-Za-z0-9_.]+\.ContextProvider\b""").replace(cleaned, cpImport)
        }

        val lines = cleaned.lines().toMutableList()
        val required = (listOf(cpImport, sourceClassImport) + extraImports).filter { it.isNotBlank() }
        val missing = required.filter { req -> lines.none { it.trim() == req } }
        if (missing.isNotEmpty()) {
            val firstImportIdx = lines.indexOfFirst { it.trim().startsWith("import ") }
            val lastImportIdx = lines.indexOfLast { it.trim().startsWith("import ") }
            if (firstImportIdx >= 0) {
                // Insertar ContextProvider y la clase fuente al principio de los imports, y los wildcards al final
                val topMissing = missing.filter { !it.endsWith("._") }
                val bottomMissing = missing.filter { it.endsWith("._") }
                if (bottomMissing.isNotEmpty()) {
                    lines.addAll(lastImportIdx + 1, bottomMissing)
                }
                if (topMissing.isNotEmpty()) {
                    lines.addAll(firstImportIdx, topMissing)
                }
            }
        }
        return CallSiteResolver.stripConstQualifiers(lines.joinToString("\n"))
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
        askAi: (String) -> String,
        outputFileOrDir: File? = null
    ): Output {

        val sourcePkg = TestPromptBuilder.extractPackage(fullSource).ifEmpty {
            TestPromptBuilder.packageFromOutputPath(sourceFile)
        }
        // Resuelve el package real a partir de la ruta de salida (o del parámetro pkg si ya traía la ruta/paquete)
        val targetPkg = if (outputFileOrDir != null) {
            TestPromptBuilder.packageFromOutputPath(outputFileOrDir, pkg)
        } else {
            TestPromptBuilder.packageFromPathString(pkg, sourcePkg)
        }

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
        val companionsText = (comp.getData + comp.imported + comp.siblings).distinct()
            .joinToString("\n\n") { f ->
                val tag = if (comp.getData.contains(f)) "GetData" else "Archivo"
                "// $tag: ${f.name}\n" + f.readText().take(20000)
            }

        progress("Resolviendo constantes, .conf y ejecutando sondas deterministas en DuckDB...")
        val allSourcesText = listOf(fullSource) + (comp.all + comp.getData).distinct().take(15).map { it.readText() }
        val constants = CallSiteResolver.stringConstants(allSourcesText)
        val listConsts = CallSiteResolver.listConstants(allSourcesText, constants)

        val sites: Map<String, List<CallSiteResolver.Site>> = if (testType == "GetData") {
            CallSiteResolver.findSites(fullSource, testable.map { it.name }, constants, configText, infos, listConsts)
        } else emptyMap()

        val detProbes = testable.mapNotNull { m ->
            CallSiteResolver.buildDeterministicProbe(
                method = m,
                site = sites[m.name]?.firstOrNull(),
                constants = constants,
                listConsts = listConsts,
                conf = configText,
                infos = infos
            )
        }
        val detRun = ParquetProbe.runProbes(detProbes, infos)
        val preMeasured = detRun.measures
        val preProbes = detProbes.filter { it.key in preMeasured }.associateBy { it.key }
        val resolvedConstsText = buildResolvedConstsSection(fullSource, constants, listConsts, configText)

        val inputs = resolveInputs(testable, constants, configText, infos)

        val composites = if (testType == "Generate") detectComposites(testable) else emptyMap()
        val aiMethods = testable.filter { it.name !in composites.keys }

        val notes = ArrayList<String>()
        if (testType == "Generate" && comp.getData.isEmpty()) {
            notes.add("No encontré la clase GetData del Map de entrada; el test no puede verificar sus claves.")
        }
        inputs.values.flatten().filter { it.parquet == null }.distinctBy { it.constName }.forEach {
            notes.add(
                if (it.table.isEmpty()) "No encontré el valor de la constante ${it.constName}; no se puede medir su input."
                else "La constante ${it.constName} (\"${it.table}\") no tiene parquet de muestra; los métodos que la usan no se pueden medir."
            )
        }
        composites.forEach { (c, l) ->
            notes.add("$c no se pidió a la IA: usa los mismos números que $l (su último paso). Si $l está mal, $c también.")
        }

        val (header, pattern) = TestPromptBuilder.headerAndPattern(
            testType, targetPkg, spec, configRel, fullSource, basePath, sourceFile, outputFileOrDir
        )
        val job = Job(
            testType = testType,
            pkg = targetPkg,
            spec = spec,
            header = header,
            pattern = pattern,
            code = code,
            companionsText = companionsText,
            configText = configText,
            infos = infos,
            mapSizes = mapSizes,
            sites = sites,
            preMeasured = preMeasured,
            preProbes = preProbes,
            resolvedConstsText = resolvedConstsText,
            inputs = inputs,
            composites = composites,
            fullSource = fullSource,
            sourceFile = sourceFile,
            basePath = basePath,
            askAi = askAi
        )

        val batches = aiMethods.chunked(BATCH_SIZE)
        val results = batches.flatMapIndexed { i, batch ->
            progress("Lote ${i + 1} de ${batches.size}: consultando a la IA y midiendo los parquet en DuckDB...")
            runBatch(job, batch)
        }

        val okParts = results.filter { it.complete }.map { it.test }
        val batchProblems = results.flatMap { it.problems }
        if (okParts.isEmpty()) {
            throw IllegalStateException(
                "Ningún lote devolvió una clase de test completa.\n" + batchProblems.joinToString("\n")
            )
        }

        var merged = mergeParts(okParts, header)
        merged = ensureProjectImports(testType, merged, targetPkg, spec, fullSource, basePath, sourceFile)
        merged = renumber(merged)

        val missing = testable
            .filter { m -> !Regex("""\b${Regex.escape(m.name)}\b""").containsMatchIn(merged) }
            .map { it.name }

        val problems = ArrayList<String>()
        problems.addAll(batchProblems)
        problems.addAll(notes)
        if (missing.isNotEmpty()) problems.add("Sin test para: ${missing.joinToString(", ")}.")

        val complete = results.all { it.complete } && balanced(merged) && missing.isEmpty()

        val review = problems.distinct()
        var finalCode = merged
        if (review.isNotEmpty()) {
            finalCode = finalCode.trimEnd() + "\n\n// PGAT - REVISAR (borra este bloque al corregir):\n" +
                    review.joinToString("\n") { "// - " + it.replace("\n", " ").replace("\r", " ") } + "\n"
        }

        return Output(
            code = finalCode,
            measured = results.flatMap { it.measured },
            problems = review,
            complete = complete
        )
    }
}