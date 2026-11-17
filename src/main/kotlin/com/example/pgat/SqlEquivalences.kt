package com.example.pgat

/**
 * Equivalencias fijas Spark -> DuckDB, aplicadas por código y no por el modelo.
 *
 *  - normalize(): reescribe lo que tiene equivalencia exacta (concat, CAST, orden de nulos, fechas)
 *    y devuelve los problemas que no se pueden traducir sin ambigüedad.
 *  - mask(), topLevel() y declaredColumns(): lectura del SQL para verificar las sondas sin ejecutar Spark.
 *
 * Todo se analiza sobre una copia enmascarada: el contenido de los strings y de los identificadores
 * entre comillas se reemplaza por '_', así no se confunden paréntesis, comas ni palabras clave
 * que vienen dentro de un literal.
 */
object SqlEquivalences {

    data class Result(val sql: String, val fixes: List<String>, val issues: List<String>)

    private class Rw(val text: String?, val issue: String? = null)

    /** Reglas que se muestran a la IA. Lo que aquí se promete, normalize() lo cumple o lo reporta. */
    val PROMPT_RULES: List<String> = listOf(
        "concat(a, b) de Spark -> (CAST(a AS VARCHAR) || CAST(b AS VARCHAR)). Spark devuelve NULL si cualquier argumento es NULL; concat() de DuckDB ignora los NULL, por eso no lo uses.",
        "CAST(x AS T) -> TRY_CAST(x AS T). Spark devuelve NULL si el valor no convierte; DuckDB daría error. Nunca uses x::T.",
        "orderBy ascendente -> ORDER BY x ASC NULLS FIRST (Spark pone los nulos primero). Descendente -> ORDER BY x DESC NULLS LAST. Escribe NULLS siempre, también dentro de OVER (...).",
        "trim(x) -> trim(CAST(x AS VARCHAR)). lpad(x, n, '0') -> lpad(CAST(x AS VARCHAR), n, '0').",
        "to_date(x, 'yyyyMMdd') -> CAST(try_strptime(CAST(x AS VARCHAR), '%Y%m%d') AS DATE). El formato va como literal con códigos strftime.",
        "date_format(x, 'yyyy-MM-dd') -> strftime(CAST(x AS TIMESTAMP), '%Y-%m-%d').",
        "Códigos de fecha: yyyy -> %Y, yy -> %y, MM -> %m, dd -> %d, HH -> %H, mm -> %M, ss -> %S.",
        "lit('x') -> 'x'. col.as(x) -> col AS x. Los nombres de columna con mayúsculas o espacios van entre comillas dobles.",
        "current_timestamp() -> CURRENT_TIMESTAMP y current_date() -> CURRENT_DATE.",
        "No traduzcas a ciegas: explode, posexplode, collect_list, collect_set, from_json, to_json, split, monotonically_increasing_id y rand. Si el método las usa, el plugin lo marcará para revisión."
    )

    private val UNSUPPORTED = listOf(
        "explode", "posexplode", "collect_list", "collect_set", "from_json",
        "to_json", "split", "monotonically_increasing_id", "rand"
    )

    private val JAVA_DATE_CHARS = Regex("[yMdHmsT\\-_/:.\\s]+")
    private val JAVA_TOKEN = Regex("yyyy|yy|MM|dd|HH|mm|ss")
    private val ORDER_BY = Regex("\\bORDER\\s+BY\\b", RegexOption.IGNORE_CASE)
    private val HAS_NULLS = Regex("\\bNULLS\\s+(FIRST|LAST)\\b", RegexOption.IGNORE_CASE)
    private val ENDS_DESC = Regex("\\bDESC$", RegexOption.IGNORE_CASE)
    private val CLAUSE_END = Regex("\\b(LIMIT|OFFSET|FETCH)\\b", RegexOption.IGNORE_CASE)
    private val CAST_CALL = Regex("(?<![A-Za-z0-9_.])CAST\\s*\\(", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    /** Reescribe el SQL con las equivalencias fijas y devuelve los problemas que no se pueden traducir. */
    fun normalize(input: String): Result {
        val fixes = ArrayList<String>()
        val issues = ArrayList<String>()
        var sql = input.trim().removeSuffix(";").trim()

        sql = convertJavaDateLiterals(sql, fixes)
        sql = rewriteCalls(sql, "to_date", "to_date -> CAST(try_strptime(...) AS DATE)", fixes, issues) { a -> toDateRw(a) }
        sql = rewriteCalls(sql, "date_format", "date_format -> strftime(CAST(...))", fixes, issues) { a -> dateFormatRw(a) }
        sql = rewriteCalls(sql, "concat", "concat -> (CAST || CAST)", fixes, issues) { a -> concatRw(a) }
        sql = fixOrderByNulls(sql, fixes)
        sql = replaceCastWithTry(sql, fixes)
        lint(sql, issues)
        return Result(sql, fixes.distinct(), issues.distinct())
    }

    /** Copia del SQL donde el contenido de strings e identificadores entre comillas es '_' (misma longitud). */
    fun mask(sql: String): String {
        val out = StringBuilder(sql.length)
        var quote: Char? = null
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val q = quote
            when {
                q == null -> {
                    if (c == '\'' || c == '"') quote = c
                    out.append(c)
                    i++
                }
                c == q && i + 1 < sql.length && sql[i + 1] == q -> {
                    out.append("__")
                    i += 2
                }
                c == q -> {
                    quote = null
                    out.append(c)
                    i++
                }
                else -> {
                    out.append('_')
                    i++
                }
            }
        }
        return out.toString()
    }

    /** SQL enmascarado donde cada subconsulta (SELECT/WITH entre paréntesis) queda como (). */
    fun topLevel(sql: String): String {
        val m = mask(sql)
        val sb = StringBuilder(m.length)
        val subquery = Regex("^\\s*(SELECT|WITH)\\b", RegexOption.IGNORE_CASE)
        var i = 0
        while (i < m.length) {
            if (m[i] == '(') {
                val close = closeOf(m, i)
                if (close > i && subquery.containsMatchIn(m.substring(i + 1, close))) {
                    sb.append("()")
                    i = close + 1
                    continue
                }
            }
            sb.append(m[i])
            i++
        }
        return sb.toString()
    }

    /** Columnas que declara el SELECT principal, o null si no se pueden contar (SELECT *, WITH, etc.). */
    fun declaredColumns(sql: String): Int? {
        val t = topLevel(sql).trim()
        val head = Regex("^SELECT\\s+(DISTINCT\\s+)?", RegexOption.IGNORE_CASE).find(t) ?: return null
        val fromPos = Regex("\\bFROM\\b", RegexOption.IGNORE_CASE).findAll(t)
            .map { it.range.first }
            .firstOrNull { pos -> pos > head.range.last && depthAt(t, pos) == 0 }
        val end = fromPos ?: t.length
        val items = splitArgs(t, head.range.last + 1, end).map { t.substring(it.first, it.second) }
        if (items.isEmpty()) return null
        if (items.any { it == "*" || it.endsWith(".*") }) return null
        return items.size
    }

    // ------------------------------------------------------------------
    // Utilidades sobre texto enmascarado
    // ------------------------------------------------------------------

    /** Índice del ')' que cierra el '(' de [open], o -1. */
    private fun closeOf(m: String, open: Int): Int {
        var depth = 0
        for (k in open until m.length) {
            when (m[k]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return k
                }
            }
        }
        return -1
    }

    /** Profundidad de paréntesis y corchetes justo antes de [pos]. */
    private fun depthAt(t: String, pos: Int): Int {
        var d = 0
        for (k in 0 until pos) {
            when (t[k]) {
                '(', '[' -> d++
                ')', ']' -> d--
            }
        }
        return d
    }

    /** Parte [from, to) por comas de nivel superior. Cada elemento es (inicio, fin) sin espacios sobrantes. */
    private fun splitArgs(m: String, from: Int, to: Int): List<Pair<Int, Int>> {
        if (from >= to || m.substring(from, to).isBlank()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var depth = 0
        var start = from
        for (k in from until to) {
            when (m[k]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) {
                    out.add(trimmed(m, start, k))
                    start = k + 1
                }
            }
        }
        out.add(trimmed(m, start, to))
        return out
    }

    private fun trimmed(m: String, from: Int, to: Int): Pair<Int, Int> {
        var s = from
        var e = to
        while (s < e && m[s].isWhitespace()) s++
        while (e > s && m[e - 1].isWhitespace()) e--
        return s to e
    }

    // ------------------------------------------------------------------
    // Reescritura de llamadas de función (soporta anidadas)
    // ------------------------------------------------------------------

    private fun rewriteCalls(
        sql: String,
        fn: String,
        label: String,
        fixes: MutableList<String>,
        issues: MutableList<String>,
        transform: (List<String>) -> Rw
    ): String {
        val rx = Regex("(?<![A-Za-z0-9_.])" + Regex.escape(fn) + "\\s*\\(", RegexOption.IGNORE_CASE)
        var cur = sql
        var from = 0
        var guard = 0
        while (guard++ < 2000) {
            val m = mask(cur)
            val hit = rx.find(m, from) ?: break
            val start = hit.range.first
            val open = hit.range.last
            val close = closeOf(m, open)
            if (close < 0) {
                from = start + 1
                continue
            }
            val args = splitArgs(m, open + 1, close).map { cur.substring(it.first, it.second) }
            val rw = transform(args)
            val replacement = rw.text
            if (replacement == null) {
                rw.issue?.let { issues.add(it) }
                from = start + 1
                continue
            }
            cur = cur.substring(0, start) + replacement + cur.substring(close + 1)
            fixes.add(label)
            from = start
        }
        return cur
    }

    private fun concatRw(a: List<String>): Rw = when (a.size) {
        0 -> Rw("''")
        1 -> Rw("CAST(${a[0]} AS VARCHAR)")
        else -> Rw("(" + a.joinToString(" || ") { "CAST($it AS VARCHAR)" } + ")")
    }

    private fun toDateRw(a: List<String>): Rw = when (a.size) {
        1 -> Rw("CAST(${a[0]} AS DATE)")
        2 -> if (isStringLiteral(a[1])) {
            Rw("CAST(try_strptime(CAST(${a[0]} AS VARCHAR), ${a[1]}) AS DATE)")
        } else {
            Rw(null, "to_date con formato que no es literal: no se puede traducir sin conocer el formato")
        }
        else -> Rw(null, "to_date con ${a.size} argumentos no soportado")
    }

    private fun dateFormatRw(a: List<String>): Rw =
        if (a.size == 2) Rw("strftime(CAST(${a[0]} AS TIMESTAMP), ${a[1]})")
        else Rw(null, "date_format con ${a.size} argumentos no soportado")

    private fun isStringLiteral(s: String): Boolean = Regex("^'(?:[^']|'')*'$").matches(s.trim())

    // ------------------------------------------------------------------
    // Patrones de fecha Java dentro de literales -> códigos strftime
    // ------------------------------------------------------------------

    private fun convertJavaDateLiterals(sql: String, fixes: MutableList<String>): String {
        val out = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c != '\'') {
                out.append(c)
                i++
                continue
            }
            val body = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < sql.length) {
                if (sql[j] == '\'') {
                    if (j + 1 < sql.length && sql[j + 1] == '\'') {
                        body.append("''")
                        j += 2
                        continue
                    }
                    closed = true
                    break
                }
                body.append(sql[j])
                j++
            }
            if (!closed) {
                out.append(sql, i, sql.length)
                break
            }
            val conv = javaToStrftime(body.toString())
            if (conv != null) {
                out.append('\'').append(conv).append('\'')
                fixes.add("patrón de fecha Java -> strftime")
            } else {
                out.append(sql, i, j + 1)
            }
            i = j + 1
        }
        return out.toString()
    }

    /** Solo convierte literales que parecen un patrón de fecha (al menos dos tokens distintos). */
    private fun javaToStrftime(body: String): String? {
        if (!JAVA_DATE_CHARS.matches(body)) return null
        if (JAVA_TOKEN.findAll(body).count() < 2) return null
        return JAVA_TOKEN.replace(body) { m ->
            when (m.value) {
                "yyyy" -> "%Y"
                "yy" -> "%y"
                "MM" -> "%m"
                "dd" -> "%d"
                "HH" -> "%H"
                "mm" -> "%M"
                "ss" -> "%S"
                else -> m.value
            }
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY: nulos explícitos (Spark: ASC nulos primero, DESC nulos al final)
    // ------------------------------------------------------------------

    private fun fixOrderByNulls(sql: String, fixes: MutableList<String>): String {
        val m = mask(sql)
        val inserts = ArrayList<Pair<Int, String>>()
        for (hit in ORDER_BY.findAll(m)) {
            val start = hit.range.last + 1
            var end = m.length
            var depth = 0
            for (k in start until m.length) {
                val ch = m[k]
                if (ch == '(' || ch == '[') {
                    depth++
                } else if (ch == ')' || ch == ']') {
                    if (depth == 0) {
                        end = k
                        break
                    }
                    depth--
                } else if (ch == ';' && depth == 0) {
                    end = k
                    break
                }
            }
            val cut = CLAUSE_END.find(m.substring(start, end))
            val realEnd = if (cut != null) start + cut.range.first else end
            for ((s, e) in splitArgs(m, start, realEnd)) {
                val item = m.substring(s, e)
                if (item.isBlank() || HAS_NULLS.containsMatchIn(item)) continue
                val nulls = if (ENDS_DESC.containsMatchIn(item)) " NULLS LAST" else " NULLS FIRST"
                inserts.add(e to nulls)
            }
        }
        if (inserts.isEmpty()) return sql
        fixes.add("ORDER BY con NULLS explícito")
        var out = sql
        for ((pos, text) in inserts.sortedByDescending { it.first }) {
            out = out.substring(0, pos) + text + out.substring(pos)
        }
        return out
    }

    // ------------------------------------------------------------------
    // CAST: Spark (sin ANSI) devuelve NULL al no convertir; DuckDB falla
    // ------------------------------------------------------------------

    private fun replaceCastWithTry(sql: String, fixes: MutableList<String>): String {
        var cur = sql
        var from = 0
        var guard = 0
        while (guard++ < 2000) {
            val hit = CAST_CALL.find(mask(cur), from) ?: break
            val s = hit.range.first
            cur = cur.substring(0, s) + "TRY_" + cur.substring(s)
            fixes.add("CAST -> TRY_CAST")
            from = s + "TRY_CAST".length
        }
        return cur
    }

    // ------------------------------------------------------------------
    // Lo que no se traduce con certeza: se reporta, no se adivina
    // ------------------------------------------------------------------

    private fun lint(sql: String, issues: MutableList<String>) {
        val m = mask(sql)
        if (m.contains("::")) issues.add("usa '::' para convertir tipos; debe ser TRY_CAST(x AS T)")
        for (fn in UNSUPPORTED) {
            if (Regex("(?<![A-Za-z0-9_.])" + fn + "\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
                issues.add("función '$fn' de Spark sin equivalencia verificada en DuckDB; no se puede medir con certeza")
            }
        }
        // Solo es un problema si current_date/current_timestamp se usa en un filtro WHERE/ON/HAVING
        val whereClause = Regex("\\b(?:WHERE|HAVING|ON)\\b[\\s\\S]*", RegexOption.IGNORE_CASE).find(m)?.value.orEmpty()
        if (Regex("\\bcurrent_(date|timestamp)\\b", RegexOption.IGNORE_CASE).containsMatchIn(whereClause)) {
            issues.add("current_date/current_timestamp en filtro WHERE/JOIN dependen de la fecha de ejecución; no se puede medir de forma repetible")
        }
    }
}