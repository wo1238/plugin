package com.example.pgat

/**
 * Genera los asserts de un test de Parametry.
 *
 * Camino 1 (local, sin IA): strings, strings multilínea, booleanos, chars,
 * números (con sufijo L/F/D y notación científica), Seq/List/Array/Vector/Set/Map.
 *
 * Camino 2 (IA, solo casos aislados): declaraciones que el camino 1 no puede
 * resolver con certeza (referencias a otras constantes, expresiones calculadas,
 * interpolaciones, objetos, etc.). Todas se envían en UNA sola llamada.
 *
 * Detecta: val, lazy val, var, private/protected/final/implicit/override val,
 * con o sin tipo explícito.
 */
object ParametryAssertGenerator {

    data class ParsedVal(val name: String, val rawValue: String)

    private data class Entry(val parsed: ParsedVal, val line: String?)

    data class Result(
        val lines: List<String>,
        val total: Int,
        val aiResolved: Int,
        val aiFallback: Int
    )

    // ------------------------------------------------------------------
    // Escáner: marca qué caracteres son código, string o comentario, y la
    // profundidad de llaves en cada posición.
    // ------------------------------------------------------------------

    private const val CODE: Byte = 0
    private const val STRING: Byte = 1
    private const val COMMENT: Byte = 2

    private class Scan(val kind: ByteArray, val depth: IntArray)

    private const val TQ = "\"\"\""

    private fun scan(src: String): Scan {
        val n = src.length
        val kind = ByteArray(n)
        val depth = IntArray(n)
        var d = 0
        var i = 0

        fun mark(from: Int, to: Int, k: Byte) {
            for (x in from until to) {
                kind[x] = k
                depth[x] = d
            }
        }

        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> {
                    val e = src.indexOf('\n', i).let { if (it < 0) n else it }
                    mark(i, e, COMMENT)
                    i = e
                }
                src.startsWith("/*", i) -> {
                    val e = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    mark(i, e, COMMENT)
                    i = e
                }
                src.startsWith(TQ, i) -> {
                    var e = src.indexOf(TQ, i + 3)
                    if (e < 0) {
                        e = n
                    } else {
                        e += 3
                        while (e < n && src[e] == '"') e++
                    }
                    mark(i, e, STRING)
                    i = e
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < n && src[j] != '"' && src[j] != '\n') {
                        if (src[j] == '\\') j++
                        j++
                    }
                    j = minOf(j + 1, n)
                    mark(i, j, STRING)
                    i = j
                }
                c == '\'' && i + 2 < n && src[i + 1] != '\\' && src[i + 2] == '\'' -> {
                    mark(i, i + 3, STRING)
                    i += 3
                }
                c == '\'' && i + 1 < n && src[i + 1] == '\\' -> {
                    val e = src.indexOf('\'', i + 2).let { if (it < 0 || it - i > 8) i + 1 else it + 1 }
                    mark(i, e, STRING)
                    i = e
                }
                else -> {
                    if (c == '{') d++
                    kind[i] = CODE
                    depth[i] = d
                    if (c == '}') d--
                    i++
                }
            }
        }
        return Scan(kind, depth)
    }

    // Inicio de una declaración: modificadores opcionales + val/var + nombre [: Tipo] =
    private val DECL_REGEX = Regex(
        """^[ \t]*(?:(?:private|protected|final|lazy|implicit|override)(?:\[[^\]]*\])?[ \t]+)*(?:val|var)[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*(?::[^=\n]+?)?[ \t]*=(?![=>])""",
        RegexOption.MULTILINE
    )

    // Una línea que empieza una nueva declaración/comentario/cierre => termina el valor anterior.
    private val NEXT_DECL = Regex(
        """^[ \t]*(?:(?:private|protected|final|lazy|implicit|override|val|var|def|object|class|trait|case|type|import|sealed|abstract)\b|//|/\*|\}|@)"""
    )

    private fun nextLineStartsDecl(src: String, from: Int): Boolean {
        var ls = from
        while (true) {
            if (ls >= src.length) return true
            var le = src.indexOf('\n', ls)
            if (le < 0) le = src.length
            val line = src.substring(ls, le)
            if (line.isBlank()) {
                ls = le + 1
                continue
            }
            return NEXT_DECL.containsMatchIn(line)
        }
    }

    /** Devuelve la posición (exclusiva) donde termina el valor que empieza en [start]. */
    private fun findValueEnd(src: String, sc: Scan, start: Int): Int {
        var j = start
        var rel = 0
        var hasValue = false
        while (j < src.length) {
            if (sc.kind[j] == CODE) {
                val ch = src[j]
                if (ch == '(' || ch == '[' || ch == '{') {
                    rel++
                } else if (ch == ')' || ch == ']' || ch == '}') {
                    rel--
                    if (rel < 0) return j
                } else if (ch == '\n') {
                    if (rel == 0 && hasValue && nextLineStartsDecl(src, j + 1)) return j
                }
            }
            if (sc.kind[j] != COMMENT && !src[j].isWhitespace()) hasValue = true
            j++
        }
        return src.length
    }

    fun extractVals(rawSource: String): List<ParsedVal> {
        // Normaliza saltos de línea de Windows para que los regex y el escáner no se confundan.
        val sourceCode = rawSource.replace("\r\n", "\n").replace('\r', '\n')
        val sc = scan(sourceCode)

        // Candidatas: declaraciones reales (no dentro de strings/comentarios) dentro de alguna llave.
        val candidates = DECL_REGEX.findAll(sourceCode).filter { m ->
            val g = m.groups[1]
            g != null && sc.kind[g.range.first] == CODE && sc.depth[g.range.first] >= 1
        }.toList()
        if (candidates.isEmpty()) return emptyList()

        // Se aceptan las del nivel más externo (el cuerpo del object), no las locales de un def.
        val level = candidates.minOf { sc.depth[it.groups[1]!!.range.first] }

        val out = ArrayList<ParsedVal>()
        var lastEnd = 0

        for (m in candidates) {
            if (m.range.first < lastEnd) continue
            val g = m.groups[1] ?: continue
            val pos = g.range.first
            if (sc.depth[pos] != level) continue

            val start = m.range.last + 1
            val end = findValueEnd(sourceCode, sc, start)

            val sb = StringBuilder()
            for (k in start until end) {
                if (sc.kind[k] != COMMENT) sb.append(sourceCode[k])
            }
            val raw = sb.toString().trim().removeSuffix(";").trim()

            out.add(ParsedVal(name = g.value, rawValue = raw))
            lastEnd = end
        }
        return out
    }

    // ------------------------------------------------------------------
    // Camino 1: asserts locales
    // ------------------------------------------------------------------

    private val COLLECTION_REGEX = Regex(
        """^(Seq|List|Array|Vector|Set|Map)(?:\[[^\]]*\])?\s*\((.*)\)$""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val NUMBER_REGEX = Regex("""^-?\d+(\.\d+)?([eE][+-]?\d+)?[LlFfDd]?$""")
    private val QUOTED_STRING_REGEX = Regex("""^"([^"\\\n]|\\.)*"$""")
    private val CHAR_REGEX = Regex("""^'(\\.|[^'\\])'$""")

    // """...""" seguido solo de .stripMargin / .trim / .stripLineEnd.
    // Si trae interpolación (s"""...""") o concatena otra cosa, NO entra aquí y va a la IA.
    private val TRIPLE_QUOTED_REGEX = Regex(
        "^" + Regex.escape(TQ) + "[\\s\\S]*?" + Regex.escape(TQ) +
                "(\\.(stripMargin(\\(.\\))?|trim|stripLineEnd))*\$"
    )

    /** Cuenta los elementos de nivel superior dentro de Seq(...)/Map(...), respetando strings y anidación. */
    internal fun countTopLevelElements(inner: String): Int {
        if (inner.isBlank()) return 0
        var depth = 0
        var count = 1
        var inString = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            i++
        }
        // coma final: Seq(a, b,)
        if (inner.trimEnd().endsWith(",")) count--
        return count
    }

    /** Assert local para un val, o null si no es un caso 100% seguro (-> IA). */
    private fun buildLocalAssert(sourceName: String, parsed: ParsedVal): String? {
        val name = parsed.name
        val raw = parsed.rawValue

        return when {
            TRIPLE_QUOTED_REGEX.matches(raw) -> "assert($sourceName.$name == $raw)"
            QUOTED_STRING_REGEX.matches(raw) -> "assert($sourceName.$name.equals($raw))"
            CHAR_REGEX.matches(raw) -> "assert($sourceName.$name == $raw)"
            raw == "true" -> "assert($sourceName.$name)"
            raw == "false" -> "assert(!$sourceName.$name)"
            NUMBER_REGEX.matches(raw) -> "assert($sourceName.$name == $raw)"

            COLLECTION_REGEX.matches(raw) -> {
                val m = COLLECTION_REGEX.find(raw)!!
                val kindName = m.groupValues[1]
                val count = countTopLevelElements(m.groupValues[2])
                if (kindName == "Set" || kindName == "Map") {
                    "assert($sourceName.$name.size == $count)"
                } else {
                    "assert($sourceName.$name.length == $count)"
                }
            }

            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Camino 2: IA, solo para los vals que el camino local no pudo resolver
    // ------------------------------------------------------------------

    private fun buildAiPrompt(sourceCode: String, sourceName: String, pending: List<ParsedVal>): String {
        val list = pending.joinToString("\n") {
            "- ${it.name} = ${it.rawValue.replace("\n", "\\n").take(400)}"
        }
        return listOf(
            "Eres un experto en Scala y ScalaTest. NO HABLES. NO EXPLIQUES. Sin bloques markdown.",
            "",
            "Código fuente completo del object '$sourceName' (úsalo para resolver referencias entre constantes):",
            sourceCode,
            "",
            "Genera UN assert para CADA una de estas constantes (y solo estas):",
            list,
            "",
            "Reglas obligatorias:",
            "1. Formato de salida: una línea por constante, exactamente: NOMBRE@@assert(...)",
            "2. Todo assert debe ir en UNA sola línea y referirse a la constante como $sourceName.NOMBRE.",
            "3. Si el valor es una referencia a otra constante: assert($sourceName.NOMBRE == $sourceName.OTRA)",
            "4. Si es una expresión calculada: assert($sourceName.NOMBRE == <expresión>), anteponiendo",
            "   '$sourceName.' a cada constante del object que use la expresión.",
            "5. Si es un String: assert($sourceName.NOMBRE.equals(\"valor\")). Si es Boolean: assert($sourceName.NOMBRE) o assert(!$sourceName.NOMBRE).",
            "6. Si es una colección Seq/List/Array/Vector: assert($sourceName.NOMBRE.length == N). Si es Set/Map: assert($sourceName.NOMBRE.size == N).",
            "7. Si es un número: assert($sourceName.NOMBRE == valor).",
            "8. Si realmente no puedes deducirlo: assert($sourceName.NOMBRE != null) // TODO: revisar manualmente",
            "9. Usa los nombres reales del código. No inventes constantes ni agregues líneas extra."
        ).joinToString("\n")
    }

    private fun parseAiResponse(response: String, sourceName: String, pending: List<ParsedVal>): Map<String, String> {
        val valid = pending.map { it.name }.toSet()
        val result = LinkedHashMap<String, String>()
        for (line in response.lines()) {
            val idx = line.indexOf("@@")
            if (idx < 0) continue
            val name = line.substring(0, idx).trim().removePrefix("-").trim()
            val assertLine = line.substring(idx + 2).trim()
            if (name in valid &&
                name !in result &&
                assertLine.startsWith("assert(") &&
                assertLine.contains("$sourceName.$name")
            ) {
                result[name] = assertLine
            }
        }
        return result
    }

    /**
     * @param askAi si es null, los casos ambiguos quedan con TODO (modo 100% local).
     */
    fun generate(sourceCode: String, sourceName: String, askAi: ((String) -> String)? = null): Result {

        val entries = extractVals(sourceCode).map { Entry(it, buildLocalAssert(sourceName, it)) }
        val pending = entries.filter { it.line == null }.map { it.parsed }

        val aiLines: Map<String, String> =
            if (pending.isNotEmpty() && askAi != null) {
                try {
                    parseAiResponse(askAi(buildAiPrompt(sourceCode, sourceName, pending)), sourceName, pending)
                } catch (ex: Exception) {
                    emptyMap()
                }
            } else emptyMap()

        var aiOk = 0
        var fallback = 0
        val lines = entries.map { e ->
            val local = e.line
            if (local != null) {
                local
            } else {
                val fromAi = aiLines[e.parsed.name]
                if (fromAi != null) {
                    aiOk++
                    fromAi
                } else {
                    fallback++
                    "assert($sourceName.${e.parsed.name} != null) // TODO: revisar manualmente (no se pudo resolver)"
                }
            }
        }

        return Result(lines, entries.size, aiOk, fallback)
    }
}