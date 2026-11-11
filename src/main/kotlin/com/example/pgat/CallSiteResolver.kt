package com.example.pgat

/**
 * Resuelve DETERMINÍSTICAMENTE, sin IA, qué parquet lee cada llamada real del código.
 * Se basa SOLO en el VALOR de la constante (no en su nombre ni en el prefijo del .conf):
 *
 *   getDefaultMasterTable2(CONFIGURACION_SFC, CONFIGSFC_COL_LIST)
 *     -> CONFIGURACION_SFC = "<prefijo>.t_cpad_utactmodsf_par_modul_sfc"   (prefijo libre: inputs, in, otro_input...)
 *     -> bloque t_cpad_utactmodsf_par_modul_sfc { table = "esquema.t_cpad_..." } en el .conf
 *     -> parquet de muestra cuyo nombre es el nombre final de esa tabla
 */
object CallSiteResolver {

    data class Site(
        val method: String,
        val args: String,
        val normArgs: String,
        val constants: List<String>,
        val tables: Set<String>
    ) {
        val call: String get() = "$method(${args.trim()})"
    }

    private val STRING_VAL = Regex(
        """(?m)\bval\s+([A-Z][A-Z0-9_]*)\s*(?::\s*String)?\s*=\s*\x22((?:[^\x22\\]|\\.)*)\x22"""
    )
    private val CONST_TOKEN = Regex("""\b[A-Z][A-Z0-9_]{2,}\b""")
    private val QUOTED = Regex("""\x22([^\x22]+)\x22""")
    private val RHS = Regex("""[=:]\s*(?:\x22([^\x22]*)\x22|([^\s\x22{},]+))""")
    private val TABLE_KEYS = Regex(
        """\b(?:table|path|paths|file|location|dir|folder)\s*[=:]\s*\[?\s*\x22([^\x22]+)\x22"""
    )

    // El test llama a los métodos como getObjectProcess.metodo(...); en el código van sin prefijo.
    private val QUALIFIER = Regex("""\b(?:getObjectProcess|this)\s*\.\s*""")

    fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Argumentos sin espacios y sin el prefijo getObjectProcess. / this. (para comparar test vs código). */
    fun normArgs(s: String): String = QUALIFIER.replace(s, "").replace(Regex("""\s+"""), "")

    fun hasConstants(args: String): Boolean = CONST_TOKEN.containsMatchIn(args)

    /** Contenido entre el paréntesis/corchete/llave de apertura en [open] y su cierre. */
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

    /** val NOMBRE = "valor" de todos los fuentes (el primero que aparece gana). */
    fun stringConstants(sources: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        sources.forEach { src ->
            STRING_VAL.findAll(src).forEach { m -> out.putIfAbsent(m.groupValues[1], m.groupValues[2]) }
        }
        return out
    }

    // ------------------------------------------------------------------
    // .conf (HOCON): clave -> bloque
    // ------------------------------------------------------------------

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Contenido del bloque `key { ... }` (también `key = { ... }` o `key: { ... }`) dentro de [text]. */
    private fun keyBlock(text: String, key: String): String? {
        if (key.isBlank() || text.isBlank()) return null
        var from = 0
        while (true) {
            val i = text.indexOf(key, from)
            if (i < 0) return null
            val end = i + key.length
            from = end

            val before = if (i == 0) ' ' else text[i - 1]
            if (isWordChar(before) || before == '.') continue   // parte de otra palabra o de "esquema.tabla"
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

    /** Recorre la ruta segmento a segmento (a { b { c { } } }); null si algún segmento no existe. */
    private fun walkPath(path: String, conf: String): String? {
        var cur = conf
        for (seg in path.split('.')) {
            cur = keyBlock(cur, seg) ?: return null
        }
        return cur
    }

    /**
     * Bloque de la constante, SIN depender del prefijo:
     *  1) ruta completa (plana o anidada)
     *  2) solo el último segmento, buscado en cualquier parte del .conf
     */
    private fun blockForPath(path: String, conf: String): String? {
        keyBlock(conf, path)?.let { return it }
        walkPath(path, conf)?.let { return it }
        val last = path.substringAfterLast('.')
        return keyBlock(conf, last)
    }

    private fun stringsIn(text: String): List<String> {
        val out = ArrayList<String>()
        QUOTED.findAll(text).forEach { out.add(it.groupValues[1]) }
        RHS.findAll(text).forEach { m -> out.add(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Textos del .conf asociados a la clave literal: su línea y, si abre un bloque { }, el contenido. */
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
        // "esquema.t_tabla" -> también se compara "t_tabla"
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

    /** Último recurso: el valor (nunca el nombre de la constante) contiene el nombre del parquet; solo si es único. */
    private fun byValue(value: String, infos: List<ParquetProbe.Info>): Set<String> {
        val key = norm(value.substringAfterLast('.').substringAfterLast('/'))
        if (key.isEmpty()) return emptySet()
        val hits = infos.filter { i ->
            val n = norm(i.name)
            n.length >= 6 && key.contains(n)
        }
        if (hits.isEmpty()) return emptySet()
        val longest = hits.maxOf { norm(it.name).length }
        val top = hits.filter { norm(it.name).length == longest }
        return if (top.size == 1) setOf(top[0].name) else emptySet()
    }

    /**
     * Orden de resolución (solo con el VALOR de la constante):
     *  1) bloque del .conf (por ruta completa o por último segmento) -> su table/path -> parquet
     *  2) texto literal del valor en el .conf
     *  3) último segmento del valor contra el nombre del parquet
     *  4) parecido de nombres (solo si es único)
     *
     * [name] se conserva en la firma por compatibilidad, pero NO se usa para decidir.
     */
    @Suppress("UNUSED_PARAMETER")
    fun tablesFor(name: String, value: String, conf: String, infos: List<ParquetProbe.Info>): Set<String> {
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

        return byValue(value, infos)
    }

    // ------------------------------------------------------------------
    // Llamadas reales en el código
    // ------------------------------------------------------------------

    fun findSites(
        source: String,
        methodNames: Collection<String>,
        constants: Map<String, String>,
        conf: String,
        infos: List<ParquetProbe.Info>
    ): Map<String, List<Site>> {
        val cache = HashMap<String, Set<String>>()
        fun tablesOfConst(n: String): Set<String> = cache.getOrPut(n) {
            val v = constants[n]
            if (v == null) emptySet() else tablesFor(n, v, conf, infos)
        }

        val out = LinkedHashMap<String, List<Site>>()
        for (name in methodNames.distinct()) {
            val sites = LinkedHashMap<String, Site>()
            Regex("""\b${Regex.escape(name)}\s*\(""").findAll(source).forEach { m ->
                val start = m.range.first
                val before = source.substring(maxOf(0, start - 8), start)
                if (Regex("""def\s+$""").containsMatchIn(before)) return@forEach
                val inner = balancedInner(source, m.range.last) ?: return@forEach
                val key = normArgs(inner)
                if (key.isEmpty() || sites.containsKey(key)) return@forEach
                val consts = CONST_TOKEN.findAll(inner).map { it.value }.distinct().toList()
                val tables = consts.flatMapTo(LinkedHashSet<String>()) { tablesOfConst(it) }
                sites[key] = Site(name, inner.trim(), key, consts, tables)
            }
            if (sites.isNotEmpty()) out[name] = sites.values.toList()
        }
        return out
    }
}