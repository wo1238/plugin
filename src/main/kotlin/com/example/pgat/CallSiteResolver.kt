package com.example.pgat

import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Resuelve DETERMINÍSTICAMENTE, sin IA:
 *  1) El valor de cada constante String, Int y List[String] (con sus elementos exactos).
 *  2) Los valores de configuración del archivo .conf (config.getString(...)).
 *  3) Qué parquet lee cada llamada real del código.
 *  4) La consulta SQL exacta (sonda DuckDB) de los métodos de lectura para que DuckDB mida las
 *     filas y columnas reales directamente sobre el parquet sin depender de que la IA adivine el SQL.
 */
object CallSiteResolver {

    data class Site(
        val method: String,
        val args: String,
        val normArgs: String,
        val constants: List<String>,
        val tables: Set<String>,
        val columns: List<String> = emptyList()
    ) {
        val call: String get() = "$method(${args.trim()})"
    }

    private val STRING_VAL = Regex(
        """(?m)\bval\s+([A-Z][A-Z0-9_]*)\s*(?::\s*String)?\s*=\s*\x22((?:[^\x22\\]|\\.)*)\x22"""
    )
    private val COLLECTION_RAW = Regex(
        """^(?:Seq|List|Array|Vector|Set)(?:\[[^\]]*\])?\s*\((.*)\)$""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val CONST_TOKEN = Regex("""\b[A-Z][A-Z0-9_]{2,}\b""")
    private val QUOTED = Regex("""\x22([^\x22]+)\x22""")
    private val RHS = Regex("""[=:]\s*(?:\x22([^\x22]*)\x22|([^\s\x22{},]+))""")
    private val TABLE_KEYS = Regex(
        """\b(?:table|path|paths|file|location|dir|folder)\s*[=:]\s*\[?\s*\x22([^\x22]+)\x22"""
    )

    private val QUALIFIER = Regex("""\b(?:getObjectProcess|this)\s*\.\s*""")
    private val CONST_QUALIFIER = Regex("""\b(?:[a-z_][a-zA-Z0-9_]*\s*\.\s*)*[A-Z][A-Za-z0-9_]*\s*\.\s*([A-Z][A-Z0-9_]{2,})\b""")

    fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun normArgs(s: String): String {
        val noQual = QUALIFIER.replace(s, "")
        val noObj = CONST_QUALIFIER.replace(noQual, "$1")
        return noObj.replace(Regex("""\s+"""), "")
    }

    fun stripConstQualifiers(s: String): String = CONST_QUALIFIER.replace(s, "$1")

    fun hasConstants(args: String): Boolean = CONST_TOKEN.containsMatchIn(args)

    fun extractConstantTokens(text: String): Set<String> =
        CONST_TOKEN.findAll(text).map { it.value }.toSet()

    fun balancedInner(s: String, open: Int): String? {
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

    /**
     * Extrae todas las constantes String de los archivos fuente, resolviendo:
     *  - literales simples: val X = "rates.inputs.tabla"
     *  - interpolaciones:   val X = s"$RATES.$INPUTS.tabla"
     *  - concatenaciones:   val X = RATES + "." + INPUTS + ".tabla"
     *  - referencias:       val X = OTRA_CONSTANTE
     */
    fun stringConstants(sources: List<String>): Map<String, String> {
        val rawMap = LinkedHashMap<String, String>()
        val resolved = LinkedHashMap<String, String>()

        for (src in sources) {
            STRING_VAL.findAll(src).forEach { m ->
                resolved.putIfAbsent(m.groupValues[1], m.groupValues[2])
            }
            for (pv in ParametryAssertGenerator.extractVals(src)) {
                rawMap.putIfAbsent(pv.name, pv.rawValue.trim())
            }
        }

        fun evalExpr(expr0: String, depth: Int): String? {
            if (depth > 8) return null
            val expr = expr0.trim()
            if (expr.isEmpty()) return null

            if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length >= 2 &&
                !expr.substring(1, expr.length - 1).contains('"')
            ) {
                return expr.substring(1, expr.length - 1)
            }

            val interpMatch = Regex("""^(?:s|f|raw)\x22((?:[^\x22\\]|\\.)*)\x22$""").find(expr)
            if (interpMatch != null) {
                val body = interpMatch.groupValues[1]
                var ok = true
                val replaced = Regex("""\$\{?([A-Za-z_][A-Za-z0-9_.]*)\}?""").replace(body) { m ->
                    val refName = m.groupValues[1].substringAfterLast('.')
                    val v = resolved[refName] ?: rawMap[refName]?.let { evalExpr(it, depth + 1) }
                    if (v != null) {
                        resolved.putIfAbsent(refName, v)
                        v
                    } else {
                        ok = false
                        m.value
                    }
                }
                if (ok) return replaced
            }

            if (expr.contains('+')) {
                val parts = splitTopLevel(expr, '+')
                if (parts.size > 1) {
                    val evalParts = parts.map { evalExpr(it, depth + 1) ?: return null }
                    return evalParts.joinToString("")
                }
            }

            val cleanRef = stripConstQualifiers(expr)
            if (Regex("""^[A-Z][A-Z0-9_]*$""").matches(cleanRef)) {
                resolved[cleanRef]?.let { return it }
                rawMap[cleanRef]?.let { raw ->
                    val v = evalExpr(raw, depth + 1)
                    if (v != null) {
                        resolved[cleanRef] = v
                        return v
                    }
                }
            }

            return null
        }

        for ((name, raw) in rawMap) {
            if (name !in resolved) {
                evalExpr(raw, 0)?.let { resolved[name] = it }
            }
        }
        return resolved
    }

    /**
     * Extrae los elementos exactos de cada constante `List[String]` / `Seq[String]` / `Array[String]`,
     * resolviendo tanto strings literales como referencias a otras constantes String (ej. `List(COL_A, COL_B)`).
     */
    fun listConstants(sources: List<String>, strConsts: Map<String, String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (src in sources) {
            for (pv in ParametryAssertGenerator.extractVals(src)) {
                val m = COLLECTION_RAW.find(pv.rawValue.trim()) ?: continue
                val inner = m.groupValues[1].trim()
                if (inner.isEmpty()) continue
                val items = splitTopLevel(inner, ',')
                    .mapNotNull { item ->
                        val t = item.trim()
                        when {
                            t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 ->
                                t.substring(1, t.length - 1)
                            else -> {
                                val clean = stripConstQualifiers(t)
                                strConsts[clean]
                            }
                        }
                    }
                if (items.isNotEmpty()) {
                    out.putIfAbsent(pv.name, items)
                }
            }
        }
        return out
    }

    private fun splitTopLevel(expr: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inStr = false
        var last = 0
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    sep -> if (depth == 0) {
                        out.add(expr.substring(last, i).trim())
                        last = i + 1
                    }
                }
            }
            i++
        }
        if (last <= expr.length) out.add(expr.substring(last).trim())
        return out.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // .conf (HOCON): clave -> bloque o valor escalar
    // ------------------------------------------------------------------

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun keyBlock(text: String, key: String): String? {
        if (key.isBlank() || text.isBlank()) return null
        var from = 0
        while (true) {
            val i = text.indexOf(key, from)
            if (i < 0) return null
            val end = i + key.length
            from = end

            val before = if (i == 0) ' ' else text[i - 1]
            if (isWordChar(before) || before == '.') continue
            if (end < text.length && isWordChar(text[end])) continue

            var j = end
            if (j < text.length && text[j] == '"') j++
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && (text[j] == '=' || text[j] == ':')) j++
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == '{') {
                balancedInner(text, j)?.let { return it }
            }
        }
    }

    private fun walkPath(path: String, conf: String): String? {
        var cur = conf
        for (seg in path.split('.')) {
            cur = keyBlock(cur, seg) ?: return null
        }
        return cur
    }

    private fun blockForPath(path: String, conf: String): String? {
        keyBlock(conf, path)?.let { return it }
        walkPath(path, conf)?.let { return it }
        val segs = path.split('.').filter { it.isNotEmpty() }
        for (i in 1 until segs.size) {
            val sub = segs.drop(i).joinToString(".")
            keyBlock(conf, sub)?.let { return it }
            walkPath(sub, conf)?.let { return it }
        }
        val last = path.substringAfterLast('.')
        return keyBlock(conf, last)
    }

    /**
     * Obtiene el valor escalar de una clave en el .conf (ej. `rates.params.yearPrev` -> `"2024"`).
     * Soporta tanto claves planas (`a.b.c = "valor"`) como bloques anidados (`a { b { c = "valor" } }`).
     */
    fun resolveConfScalar(path: String, conf: String): String? {
        if (path.isBlank() || conf.isBlank()) return null

        fun findScalarIn(text: String, key: String): String? {
            val rx = Regex("""(?m)(?:^|[\s{,])\x22?${Regex.escape(key)}\x22?\s*[=:]\s*(?:\x22([^\x22]*)\x22|([^\s\x22{},#]+))""")
            val m = rx.find(text) ?: return null
            return (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        }

        findScalarIn(conf, path)?.let { return it }

        val segs = path.split('.').filter { it.isNotEmpty() }
        if (segs.size >= 2) {
            val parentPath = segs.dropLast(1).joinToString(".")
            val leaf = segs.last()
            blockForPath(parentPath, conf)?.let { blk ->
                findScalarIn(blk, leaf)?.let { return it }
            }
        }
        return findScalarIn(conf, path.substringAfterLast('.'))
    }

    private fun stringsIn(text: String): List<String> {
        val out = ArrayList<String>()
        QUOTED.findAll(text).forEach { out.add(it.groupValues[1]) }
        RHS.findAll(text).forEach { m -> out.add(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun confStrings(key: String, conf: String): List<String> {
        if (key.isBlank() || conf.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val i = conf.indexOf(key, from)
            if (i < 0) break
            val end = i + key.length
            from = end
            val okBefore = i == 0 || !isWordChar(conf[i - 1])
            val okAfter = end >= conf.length || !isWordChar(conf[end])
            if (!okBefore || !okAfter) continue

            val ls = conf.lastIndexOf('\n', i) + 1
            val le = conf.indexOf('\n', i).let { if (it < 0) conf.length else it }
            out.addAll(stringsIn(conf.substring(ls, le)))

            var j = end
            while (j < conf.length && (conf[j].isWhitespace() || conf[j] == '"' || conf[j] == '=' || conf[j] == ':')) j++
            if (j < conf.length && conf[j] == '{') {
                balancedInner(conf, j)?.let { out.addAll(stringsIn(it.take(4000))) }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // texto -> parquet
    // ------------------------------------------------------------------

    private fun matchOne(cand: String, infos: List<ParquetProbe.Info>): List<ParquetProbe.Info> {
        val c = cand.replace("\\", "/").trim().trimEnd('/')
        if (c.isEmpty()) return emptyList()
        val last = c.substringAfterLast('/')
        val keys = setOf(norm(last), norm(last.substringAfterLast('.'))).filter { it.isNotEmpty() }
        return infos.filter { i ->
            val rel = i.relPath.replace("\\", "/").trimEnd('/')
            rel.equals(c, ignoreCase = true) ||
                    rel.endsWith("/$c", ignoreCase = true) ||
                    c.endsWith("/$rel", ignoreCase = true) ||
                    keys.any { it == norm(i.name) }
        }
    }

    private fun matchAll(cands: List<String>, infos: List<ParquetProbe.Info>): Set<String> =
        cands.flatMap { matchOne(it, infos) }.map { it.name }.toCollection(LinkedHashSet())

    private fun byValue(value: String, infos: List<ParquetProbe.Info>): Set<String> {
        val key = norm(value.substringAfterLast('.').substringAfterLast('/'))
        if (key.isEmpty()) return emptySet()
        val hits = infos.filter { i ->
            val n = norm(i.name)
            n.length >= 6 && (key.contains(n) || n.contains(key))
        }
        if (hits.isEmpty()) return emptySet()
        val longest = hits.maxOf { norm(it.name).length }
        val top = hits.filter { norm(it.name).length == longest }
        return if (top.size == 1) setOf(top[0].name) else emptySet()
    }

    fun tablesFor(name: String, value: String, conf: String, infos: List<ParquetProbe.Info>): Set<String> {
        if (value.isNotBlank()) {
            val block = blockForPath(value, conf)
            if (block != null) {
                val cands = TABLE_KEYS.findAll(block).map { it.groupValues[1] }.toList()
                    .ifEmpty { stringsIn(block.take(4000)) }
                val hit = matchAll(cands, infos)
                if (hit.isNotEmpty()) return hit
            }

            val fromConf = matchAll(confStrings(value, conf), infos)
            if (fromConf.isNotEmpty()) return fromConf

            val direct = matchAll(listOf(value, value.substringAfterLast('.')), infos)
            if (direct.isNotEmpty()) return direct

            val byVal = byValue(value, infos)
            if (byVal.isNotEmpty()) return byVal
        }

        if (name.isNotBlank()) {
            val cleanName = name.removePrefix("INPUT_").removePrefix("TABLE_")
            val blockByName = blockForPath(cleanName.lowercase(), conf)
            if (blockByName != null) {
                val cands = TABLE_KEYS.findAll(blockByName).map { it.groupValues[1] }.toList()
                    .ifEmpty { stringsIn(blockByName.take(4000)) }
                val hit = matchAll(cands, infos)
                if (hit.isNotEmpty()) return hit
            }
            val directByName = matchAll(listOf(cleanName), infos)
            if (directByName.isNotEmpty()) return directByName
        }

        return emptySet()
    }

    // ------------------------------------------------------------------
    // Llamadas reales en el código
    // ------------------------------------------------------------------

    fun findSites(
        source: String,
        methodNames: Collection<String>,
        constants: Map<String, String>,
        conf: String,
        infos: List<ParquetProbe.Info>,
        listConsts: Map<String, List<String>> = emptyMap()
    ): Map<String, List<Site>> {
        val cache = HashMap<String, Set<String>>()
        fun tablesOfConst(n: String): Set<String> = cache.getOrPut(n) {
            val v = constants[n].orEmpty()
            tablesFor(n, v, conf, infos)
        }

        val out = LinkedHashMap<String, List<Site>>()
        for (name in methodNames.distinct()) {
            val sites = LinkedHashMap<String, Site>()
            Regex("""\b${Regex.escape(name)}\s*\(""").findAll(source).forEach { m ->
                val start = m.range.first
                val before = source.substring(maxOf(0, start - 8), start)
                if (Regex("""def\s+$""").containsMatchIn(before)) return@forEach
                val inner = balancedInner(source, m.range.last) ?: return@forEach
                val cleanInner = stripConstQualifiers(inner.trim()).replace(Regex("""\s+"""), " ")
                val key = normArgs(cleanInner)
                if (key.isEmpty() || sites.containsKey(key)) return@forEach
                val consts = CONST_TOKEN.findAll(cleanInner).map { it.value }.distinct().toList()
                val tables = consts.flatMapTo(LinkedHashSet<String>()) { tablesOfConst(it) }
                val cols = consts.firstNotNullOfOrNull { listConsts[it] }.orEmpty()
                sites[key] = Site(name, cleanInner, key, consts, tables, cols)
            }
            if (sites.isNotEmpty()) out[name] = sites.values.toList()
        }
        return out
    }

    // ------------------------------------------------------------------
    // Generador determinista de sondas SQL para DuckDB
    // ------------------------------------------------------------------

    private fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"

    /**
     * Si el método corresponde a un patrón de lectura determinista (ReaderWithDataproc con o sin filtro de
     * partición/corte, que devuelve las columnas de `columnList`), construye la consulta SQL exacta que
     * DuckDB ejecutará sobre el parquet real.
     *
     * Así DuckDB mide el parquet directamente con las columnas y filtros reales del código y del .conf,
     * sin que la IA pueda equivocarse en los nombres de columnas ni en los filtros.
     */
    fun buildDeterministicProbe(
        method: MeasuredTestBuilder.Method,
        site: Site?,
        constants: Map<String, String>,
        listConsts: Map<String, List<String>>,
        conf: String,
        infos: List<ParquetProbe.Info>
    ): ParquetProbe.Probe? {
        val body = method.body
        // Solo generamos sonda determinista directa para métodos que leen con ReaderWithDataproc
        // y no hacen joins ni agregaciones intermedias complejas.
        if (!body.contains("ReaderWithDataproc")) return null
        if (Regex("""\b(?:join|groupBy|agg|union|unionByName)\s*\(""").containsMatchIn(body)) return null

        val tableName = site?.tables?.firstOrNull()
            ?: extractConstantTokens(body).firstNotNullOfOrNull { c ->
                tablesFor(c, constants[c].orEmpty(), conf, infos).firstOrNull()
            }
            ?: return null

        val info = infos.firstOrNull { it.name.equals(tableName, ignoreCase = true) } ?: return null
        val realColsByLower = info.columns.associateBy { it.lowercase() }

        val targetCols: List<String> = when {
            site != null && site.columns.isNotEmpty() -> site.columns
            else -> extractConstantTokens(body).firstNotNullOfOrNull { listConsts[it] }.orEmpty()
        }
        if (targetCols.isEmpty()) return null

        val usesTrim = body.contains("trim(")
        val selectList = targetCols.joinToString(", ") { colName ->
            val realCol = realColsByLower[colName.lowercase()] ?: colName
            if (usesTrim) {
                "trim(CAST(${q(realCol)} AS VARCHAR)) AS ${q(colName)}"
            } else {
                "${q(realCol)} AS ${q(colName)}"
            }
        }

        // Resolver nombres reales de columnas de partición a partir de las constantes del método o del parquet
        fun resolvePartCol(defaultSubstr: String): String? {
            val fromConst = extractConstantTokens(body)
                .firstOrNull { it.contains(defaultSubstr, ignoreCase = true) }
                ?.let { constants[it] }
            if (fromConst != null && realColsByLower.containsKey(fromConst.lowercase())) {
                return realColsByLower[fromConst.lowercase()]
            }
            return info.columns.firstOrNull { it.contains(defaultSubstr, ignoreCase = true) }
        }

        val yearCol = resolvePartCol("year")
        val monthCol = resolvePartCol("month")
        val dayCol = resolvePartCol("day")

        val hasYearMonth = body.contains("PARTITION_DATA_YEAR_ID") || (yearCol != null && monthCol != null && body.contains("concat("))
        val hasDay = body.contains("PARTITION_DATA_DAY_ID") || (dayCol != null && body.contains("DAY"))

        if (!hasYearMonth || yearCol == null || monthCol == null) {
            // Patrón simple sin filtro de partición (ej. getDefaultMasterTable)
            return ParquetProbe.Probe(
                key = method.name,
                code = "SELECT $selectList FROM ${q(info.name)}"
            )
        }

        val partExpr = if (hasDay && dayCol != null) {
            "concat(CAST(${q(yearCol)} AS VARCHAR), lpad(CAST(${q(monthCol)} AS VARCHAR), 2, '0'), lpad(CAST(${q(dayCol)} AS VARCHAR), 2, '0'))"
        } else {
            "concat(CAST(${q(yearCol)} AS VARCHAR), lpad(CAST(${q(monthCol)} AS VARCHAR), 2, '0'))"
        }

        // ¿Tiene filtro <= cutoffDate basado en config.getString(...)?
        val configKeys = Regex("""config\.getString\(\s*([A-Z][A-Z0-9_]*)\s*\)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

        if (configKeys.isNotEmpty() && body.contains("<=")) {
            val yearKey = configKeys.firstOrNull { it.contains("YEAR", ignoreCase = true) } ?: configKeys.getOrNull(0)
            val monthKey = configKeys.firstOrNull { it.contains("MONTH", ignoreCase = true) } ?: configKeys.getOrNull(1)
            val yearVal = yearKey?.let { k -> resolveConfScalar(constants[k].orEmpty(), conf) }
            val monthVal = monthKey?.let { k -> resolveConfScalar(constants[k].orEmpty(), conf) }

            if (!yearVal.isNullOrBlank() && !monthVal.isNullOrBlank()) {
                val mPad = monthVal.padStart(2, '0')
                val cutoff = if (hasDay && body.contains("atEndOfMonth")) {
                    val lastDay = try {
                        YearMonth.parse(yearVal + mPad, DateTimeFormatter.ofPattern("yyyyMM"))
                            .atEndOfMonth().dayOfMonth.toString().padStart(2, '0')
                    } catch (_: Exception) {
                        "31"
                    }
                    "$yearVal$mPad$lastDay"
                } else {
                    "$yearVal$mPad"
                }

                val sql = "SELECT $selectList FROM ${q(info.name)} " +
                        "WHERE $partExpr = (SELECT max($partExpr) FROM ${q(info.name)} WHERE $partExpr <= ${lit(cutoff)})"
                return ParquetProbe.Probe(key = method.name, code = sql)
            }
        }

        // Filtro de partición máxima sin corte previo (ej. getDefaultMasterTable2, getLatestInCutoffMonth)
        val sql = "SELECT $selectList FROM ${q(info.name)} " +
                "WHERE $partExpr = (SELECT max($partExpr) FROM ${q(info.name)})"
        return ParquetProbe.Probe(key = method.name, code = sql)
    }
}