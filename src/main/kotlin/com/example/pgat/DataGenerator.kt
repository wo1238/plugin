package com.example.pgat

import com.google.gson.JsonParser
import java.io.File
import java.sql.Connection
import java.time.LocalDate
import java.util.Properties

enum class FieldMode { AUTO, AI, VALUES }

enum class PartitionStorage { FOLDERS, COLUMN }

/**
 * Cómo se llena un campo:
 *  - AUTO:   valores automáticos según el tipo (sin IA).
 *  - AI:     la IA inventa valores realistas; [text] es una indicación opcional ("nombres de clientes").
 *  - VALUES: el desarrollador da los valores en [text], separados por coma (uno solo = valor fijo).
 */
data class FieldSpec(
    val field: SchemaField,
    val mode: FieldMode,
    val text: String,
    val partition: Boolean
)

data class GenerateRequest(
    val schema: SchemaDef,
    val specs: List<FieldSpec>,
    val records: Int,
    val edgeCases: Boolean,
    val storage: PartitionStorage,
    val outputRoot: File
)

data class GenerateResult(
    val outputDir: File,
    val rows: Long,
    val partitionCols: List<String>,
    val files: Int,
    val warnings: List<String>
)

data class ModifyResult(
    val outputDir: File,
    val changedRows: Long,
    val totalRows: Long,
    val partitionCols: List<String>
)

data class ParquetColumn(val name: String, val type: String, val isPartition: Boolean)

data class ParquetInfo(val columns: List<ParquetColumn>, val partitions: List<String>, val rows: Long)

/** Un valor que existe hoy en una columna y cuántas filas lo tienen (value == null: filas con nulo). */
data class ValueCount(val value: String?, val count: Long)

data class ColumnValues(val values: List<ValueCount>, val distinct: Long, val truncated: Boolean)

/**
 * Escribe parquet con DuckDB embebido (no necesita Scala ni Spark instalados).
 * DuckDB escribe parquet estándar con compresión snappy, que Spark lee sin problema.
 */
object DataGenerator {

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun connect(): Connection =
        org.duckdb.DuckDBDriver().connect("jdbc:duckdb:", Properties())
            ?: throw IllegalStateException("No se pudo iniciar DuckDB.")

    private fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"
    private fun path(f: File) = f.absolutePath.replace("\\", "/")

    private fun exec(conn: Connection, sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    private fun scalarLong(conn: Connection, sql: String): Long =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun countParquet(root: File): Int =
        root.walkTopDown().count { it.isFile && it.name.endsWith(".parquet") }

    /** Valores por defecto para una columna de partición (el desarrollador los puede editar). */
    fun defaultPartitionValues(f: SchemaField): String {
        val n = f.name.lowercase()
        val now = LocalDate.now()
        return when {
            "year" in n -> now.year.toString()
            "month" in n -> "%02d".format(now.monthValue)
            "day" in n -> "%02d".format(now.dayOfMonth)
            f.kind == FieldKind.DATE || "date" in n -> now.toString()
            f.kind == FieldKind.INT || f.kind == FieldKind.LONG || f.kind == FieldKind.SHORT -> "1,2"
            else -> "A,B"
        }
    }

    /** "a, b, NULL" -> ["a","b",null]. Un solo valor = valor fijo. */
    private fun parseValues(text: String): List<String?> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it == "NULL") null else it }

    // ------------------------------------------------------------------
    // Expresiones SQL por campo
    // ------------------------------------------------------------------

    private fun listExpr(f: SchemaField, values: List<String?>, indexExpr: String): String {
        val t = f.duckType
        val items = values.joinToString(", ") { v ->
            if (v == null) "CAST(NULL AS $t)" else "CAST(${lit(v)} AS $t)"
        }
        return "list_extract([$items], $indexExpr)"
    }

    private fun randomIndex(k: Int) = "1 + CAST(floor(random() * $k) AS INTEGER)"

    private fun autoExpr(f: SchemaField, edge: Boolean): String {
        val t = f.duckType
        var e: String = when (f.kind) {
            FieldKind.STRING -> {
                val len = minOf(f.length ?: 20, 64)
                if (f.pk) {
                    "upper(left(concat(md5(CAST(pgat_i AS VARCHAR) || ${lit(f.name)}), " +
                            "md5(CAST(pgat_i AS VARCHAR) || ${lit(f.name + "_2")})), $len))"
                } else {
                    "upper(left(concat(md5(CAST(random() AS VARCHAR)), md5(CAST(random() AS VARCHAR))), $len))"
                }
            }
            FieldKind.SHORT -> "CAST(floor(random() * 30000) AS SMALLINT)"
            FieldKind.INT -> if (f.pk) "pgat_i + 1" else "CAST(floor(random() * 1000000) AS INTEGER)"
            FieldKind.LONG -> if (f.pk) "pgat_i + 1" else "CAST(floor(random() * 1000000000) AS BIGINT)"
            FieldKind.FLOAT -> "CAST(round(random() * 10000, 2) AS FLOAT)"
            FieldKind.DOUBLE -> "round(random() * 10000, 4)"
            FieldKind.DECIMAL -> {
                val p = f.precision ?: 18
                val s = f.scale ?: 2
                if (f.pk) "pgat_i + 1"
                else "round(random() * greatest(least(power(10, ${p - s}) - 1, 1000000), 0), $s)"
            }
            FieldKind.BOOLEAN -> "(random() < 0.5)"
            FieldKind.DATE -> "(DATE '2020-01-01' + CAST(floor(random() * 2500) AS INTEGER))"
            FieldKind.TIMESTAMP ->
                "(TIMESTAMP '2020-01-01 00:00:00' + INTERVAL (CAST(floor(random() * 86400 * 2500) AS BIGINT)) SECOND)"
        }

        if (edge && !f.pk) {
            e = when (f.kind) {
                FieldKind.STRING -> "CASE WHEN random() < 0.04 THEN '' ELSE $e END"
                FieldKind.INT ->
                    "CASE WHEN random() < 0.03 THEN 2147483647 WHEN random() < 0.03 THEN -2147483648 ELSE $e END"
                FieldKind.LONG ->
                    "CASE WHEN random() < 0.03 THEN 9223372036854775807 " +
                            "WHEN random() < 0.03 THEN -9223372036854775807 ELSE $e END"
                FieldKind.DECIMAL -> "CASE WHEN random() < 0.03 THEN 0 ELSE $e END"
                FieldKind.DATE ->
                    "CASE WHEN random() < 0.03 THEN DATE '1900-01-01' WHEN random() < 0.03 THEN DATE '2099-12-31' ELSE $e END"
                else -> e
            }
            if (f.nullable) e = "CASE WHEN random() < 0.08 THEN NULL ELSE $e END"
        }
        return "CAST($e AS $t)"
    }

    // ------------------------------------------------------------------
    // IA: un solo prompt para todos los campos en modo IA
    // ------------------------------------------------------------------

    private fun fetchAiValues(
        schema: SchemaDef,
        specs: List<FieldSpec>,
        askAi: ((String) -> String)?,
        warnings: MutableList<String>
    ): Map<String, List<String>> {
        if (askAi == null) {
            warnings.add("No hay cliente de IA: los campos en modo IA se generaron en automático.")
            return emptyMap()
        }
        val lines = specs.joinToString("\n") { s ->
            val f = s.field
            val extra = buildString {
                append("tipo ${f.rawType}")
                if (f.kind == FieldKind.STRING) append(", longitud máxima ${f.length}")
                if (f.kind == FieldKind.DATE) append(", formato yyyy-MM-dd")
                if (f.kind == FieldKind.TIMESTAMP) append(", formato yyyy-MM-dd HH:mm:ss")
            }
            val hint = if (s.text.isNotBlank()) " | indicación: ${s.text.trim()}" else ""
            "- ${f.name} ($extra)$hint"
        }
        val prompt = listOf(
            "Eres un generador de datos de prueba. NO HABLES. NO EXPLIQUES. Sin bloques markdown.",
            "",
            "Tabla: ${schema.name}${if (schema.doc.isNotBlank()) " (${schema.doc})" else ""}",
            "",
            "Devuelve SOLO un objeto JSON válido con una clave por campo y, como valor, una lista de 15 valores",
            "realistas y distintos entre sí (contexto bancario colombiano, es-CO):",
            lines,
            "",
            "Reglas:",
            "- Todos los valores son strings JSON. Decimales con punto. Respeta la longitud máxima.",
            "- Fechas yyyy-MM-dd y timestamps yyyy-MM-dd HH:mm:ss.",
            "- Usa exactamente los nombres de campo dados. Sin texto fuera del JSON."
        ).joinToString("\n")

        val out = LinkedHashMap<String, List<String>>()
        try {
            val raw = askAi(prompt)
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) throw IllegalStateException("la respuesta no es JSON")
            val obj = JsonParser.parseString(raw.substring(a, b + 1)).asJsonObject
            for (s in specs) {
                val arr = obj.get(s.field.name)?.takeIf { it.isJsonArray }?.asJsonArray
                val vals = arr?.mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }
                    ?.filter { it.isNotEmpty() }
                    ?.map { v -> if (s.field.kind == FieldKind.STRING) v.take(s.field.length ?: 20) else v }
                    ?.distinct()
                if (vals.isNullOrEmpty()) {
                    warnings.add("La IA no devolvió valores para '${s.field.name}': se generó en automático.")
                } else {
                    out[s.field.name] = vals
                }
            }
        } catch (ex: Exception) {
            warnings.add("Falló la consulta a la IA (${ex.message.orEmpty().take(150)}): campos en modo IA en automático.")
        }
        return out
    }

    // ------------------------------------------------------------------
    // Modo 1: data nueva
    // ------------------------------------------------------------------

    fun generate(req: GenerateRequest, askAi: ((String) -> String)? = null): GenerateResult {
        val warnings = ArrayList<String>()
        val partCols = req.specs.filter { it.partition }.map { it.field.name }

        val aiValues = fetchAiValues(
            req.schema,
            req.specs.filter { it.mode == FieldMode.AI && !it.partition },
            askAi,
            warnings
        )

        // Valores de cada partición y "paso" para que se cubran TODAS las combinaciones.
        val partValues = LinkedHashMap<String, List<String?>>()
        val stride = HashMap<String, Long>()
        var acc = 1L
        for (s in req.specs.filter { it.partition }) {
            var vals = parseValues(s.text)
            if (vals.isEmpty()) vals = parseValues(defaultPartitionValues(s.field))
            partValues[s.field.name] = vals
            stride[s.field.name] = acc
            acc *= vals.size
        }
        if (partCols.isNotEmpty() && req.records < acc) {
            warnings.add("Hay $acc combinaciones de partición y solo ${req.records} registros: algunas combinaciones quedarán sin data.")
        }

        val exprs = req.specs.map { s ->
            val f = s.field
            val e: String = when {
                s.partition -> {
                    val vals = partValues[f.name]!!
                    listExpr(f, vals, "1 + CAST(floor(pgat_i / ${stride[f.name]}) AS BIGINT) % ${vals.size}")
                }
                s.mode == FieldMode.VALUES -> {
                    val vals = parseValues(s.text)
                    if (vals.isEmpty()) {
                        warnings.add("'${f.name}' está en modo Valores sin valores: se generó en automático.")
                        autoExpr(f, req.edgeCases)
                    } else {
                        listExpr(f, vals, randomIndex(vals.size))
                    }
                }
                s.mode == FieldMode.AI -> {
                    val vals = aiValues[f.name]
                    if (vals != null) listExpr(f, vals, randomIndex(vals.size)) else autoExpr(f, req.edgeCases)
                }
                else -> autoExpr(f, req.edgeCases)
            }
            "$e AS ${q(f.name)}"
        }

        val select = "SELECT\n  " + exprs.joinToString(",\n  ") + "\nFROM range(${req.records}) t(pgat_i)"

        val outDir = File(req.outputRoot, req.schema.name)
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.absoluteFile.parentFile?.mkdirs()

        val usePartitions = req.storage == PartitionStorage.FOLDERS && partCols.isNotEmpty()
        var rows = 0L

        connect().use { conn ->
            exec(conn, "SET threads TO 1") // un archivo por partición (como coalesce(1))
            if (usePartitions) {
                exec(
                    conn,
                    "COPY ($select) TO ${lit(path(outDir))} " +
                            "(FORMAT PARQUET, COMPRESSION SNAPPY, PARTITION_BY (${partCols.joinToString(", ") { q(it) }}))"
                )
            } else {
                outDir.mkdirs()
                exec(
                    conn,
                    "COPY ($select) TO ${lit(path(File(outDir, "part-00000.parquet")))} (FORMAT PARQUET, COMPRESSION SNAPPY)"
                )
            }
            rows = scalarLong(
                conn,
                "SELECT count(*) FROM read_parquet(${lit(path(outDir) + "/**/*.parquet")}, " +
                        "hive_partitioning=true, union_by_name=true)"
            )
        }

        if (rows != req.records.toLong()) {
            warnings.add("Se pidieron ${req.records} registros y el parquet tiene $rows.")
        }
        return GenerateResult(outDir, rows, if (usePartitions) partCols else emptyList(), countParquet(outDir), warnings)
    }

    // ------------------------------------------------------------------
    // Modo 2: modificar un parquet existente
    // ------------------------------------------------------------------

    /** Detecta las columnas de partición mirando carpetas clave=valor (en orden). */
    fun detectPartitions(dir: File): List<String> {
        val names = ArrayList<String>()
        var cur = dir
        while (true) {
            val sub = cur.listFiles()
                ?.filter { it.isDirectory && it.name.contains("=") && !it.name.startsWith("_") }
                ?.minByOrNull { it.name }
                ?: break
            names.add(sub.name.substringBefore("="))
            cur = sub
        }
        return names
    }

    /** read_parquet de la carpeta; las particiones se leen como texto para no perder ceros ('01' no es 1). */
    private fun openSource(conn: Connection, dir: File, parts: List<String>): String {
        fun src(typed: Boolean): String {
            val types = if (typed && parts.isNotEmpty())
                ", hive_types={" + parts.joinToString(", ") { "${lit(it)}: 'VARCHAR'" } + "}"
            else ""
            return "read_parquet(${lit(path(dir) + "/**/*.parquet")}, hive_partitioning=true, union_by_name=true$types)"
        }
        val typed = src(true)
        return try {
            scalarLong(conn, "SELECT count(*) FROM $typed")
            typed
        } catch (ex: Exception) {
            src(false)
        }
    }

    /** Columnas (con su tipo), particiones y total de filas de un parquet existente. */
    fun inspect(dir: File): ParquetInfo {
        if (!dir.isDirectory) throw IllegalStateException("La carpeta no existe: ${dir.path}")
        if (countParquet(dir) == 0) throw IllegalStateException("No hay archivos .parquet en esa carpeta.")
        val parts = detectPartitions(dir)
        connect().use { conn ->
            val srcSql = openSource(conn, dir, parts)
            val rows = scalarLong(conn, "SELECT count(*) FROM $srcSql")
            val cols = ArrayList<ParquetColumn>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM $srcSql LIMIT 0").use { rs ->
                    val md = rs.metaData
                    for (i in 1..md.columnCount) {
                        val n = md.getColumnName(i)
                        cols.add(ParquetColumn(n, md.getColumnTypeName(i), n in parts))
                    }
                }
            }
            return ParquetInfo(cols, parts, rows)
        }
    }

    /** Valores que tiene hoy una columna (los [limit] más frecuentes) con su cantidad de filas. */
    fun distinctValues(dir: File, column: String, limit: Int = 200): ColumnValues {
        val parts = detectPartitions(dir)
        connect().use { conn ->
            val srcSql = openSource(conn, dir, parts)
            val c = q(column)
            val distinct = scalarLong(
                conn,
                "SELECT count(DISTINCT $c) + (CASE WHEN count(*) > count($c) THEN 1 ELSE 0 END) FROM $srcSql"
            )
            val list = ArrayList<ValueCount>()
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT CAST($c AS VARCHAR) AS v, count(*) AS n FROM $srcSql GROUP BY 1 ORDER BY n DESC, v LIMIT $limit"
                ).use { rs ->
                    while (rs.next()) list.add(ValueCount(rs.getString(1), rs.getLong(2)))
                }
            }
            return ColumnValues(list, distinct, distinct > list.size)
        }
    }

    /**
     * Cambia en [column] el valor [oldValue] por [newValue] y reescribe el parquet respetando sus particiones.
     * Escribe primero a una carpeta temporal y luego la reemplaza (no se puede sobrescribir lo que se está leyendo).
     */
    fun modify(inputDir: File, column: String, oldValue: String?, newValue: String): ModifyResult {
        if (!inputDir.isDirectory) throw IllegalStateException("La carpeta del parquet no existe: ${inputDir.path}")
        if (column.isBlank()) throw IllegalStateException("Indica la columna a modificar.")

        val parts = detectPartitions(inputDir)
        val tmp = File(inputDir.absoluteFile.parentFile, inputDir.name + "__pgat_tmp")
        if (tmp.exists()) tmp.deleteRecursively()

        var changed = 0L
        var total = 0L

        connect().use { conn ->
            exec(conn, "SET threads TO 1")

            fun src(typed: Boolean): String {
                val types = if (typed && parts.isNotEmpty())
                    ", hive_types={" + parts.joinToString(", ") { "${lit(it)}: 'VARCHAR'" } + "}"
                else ""
                return "read_parquet(${lit(path(inputDir) + "/**/*.parquet")}, hive_partitioning=true, union_by_name=true$types)"
            }

            // Las particiones se leen como texto para no perder ceros ('01' no debe volverse 1).
            var srcSql = src(true)
            try {
                total = scalarLong(conn, "SELECT count(*) FROM $srcSql")
            } catch (ex: Exception) {
                srcSql = src(false)
                total = scalarLong(conn, "SELECT count(*) FROM $srcSql")
            }

            var realName: String? = null
            var typeName = "VARCHAR"
            val allCols = ArrayList<String>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM $srcSql LIMIT 0").use { rs ->
                    val md = rs.metaData
                    for (i in 1..md.columnCount) {
                        allCols.add(md.getColumnName(i))
                        if (realName == null && md.getColumnName(i).equals(column.trim(), ignoreCase = true)) {
                            realName = md.getColumnName(i)
                            typeName = md.getColumnTypeName(i)
                        }
                    }
                }
            }
            val col = realName
                ?: throw IllegalStateException("La columna '$column' no existe. Columnas: ${allCols.joinToString(", ")}")

            // oldValue == null significa "filas que hoy tienen nulo"
            val cond = if (oldValue == null) "${q(col)} IS NULL"
            else "CAST(${q(col)} AS VARCHAR) = ${lit(oldValue)}"
            changed = scalarLong(conn, "SELECT count(*) FROM $srcSql WHERE $cond")
            if (changed == 0L) {
                throw IllegalStateException("No hay filas con $col = '$oldValue'. No se modificó nada.")
            }

            val newExpr = if (newValue == "NULL") "CAST(NULL AS $typeName)" else "CAST(${lit(newValue)} AS $typeName)"
            val select = "SELECT * REPLACE (CASE WHEN $cond " +
                    "THEN $newExpr ELSE ${q(col)} END AS ${q(col)}) FROM $srcSql"

            if (parts.isNotEmpty()) {
                exec(
                    conn,
                    "COPY ($select) TO ${lit(path(tmp))} " +
                            "(FORMAT PARQUET, COMPRESSION SNAPPY, PARTITION_BY (${parts.joinToString(", ") { q(it) }}))"
                )
            } else {
                tmp.mkdirs()
                exec(
                    conn,
                    "COPY ($select) TO ${lit(path(File(tmp, "part-00000.parquet")))} (FORMAT PARQUET, COMPRESSION SNAPPY)"
                )
            }

            val written = scalarLong(
                conn,
                "SELECT count(*) FROM read_parquet(${lit(path(tmp) + "/**/*.parquet")}, hive_partitioning=true, union_by_name=true)"
            )
            if (written != total) {
                tmp.deleteRecursively()
                throw IllegalStateException("La reescritura tiene $written filas y el original $total. No se reemplazó nada.")
            }
        }

        // Reemplazo (la conexión ya está cerrada, no quedan archivos bloqueados).
        inputDir.deleteRecursively()
        if (!tmp.renameTo(inputDir)) {
            tmp.copyRecursively(inputDir, overwrite = true)
            tmp.deleteRecursively()
        }
        return ModifyResult(inputDir, changed, total, parts)
    }
}