package com.example.pgat

import java.io.File
import java.sql.Connection
import java.util.Properties

/**
 * Lee los parquet de muestra y ejecuta las "sondas" (SQL que reproduce un método)
 * con DuckDB embebido: no requiere instalar Scala ni Spark en la máquina.
 */
object ParquetProbe {

    data class Info(
        val name: String,
        val relPath: String,
        val absPath: String,
        val rows: Long,
        val columns: List<String>,
        val colTypes: List<String> = emptyList()
    )

    data class Inspection(val infos: List<Info>, val errors: List<String>)
    data class Probe(val key: String, val code: String)
    data class Measure(val rows: Long, val cols: Int)
    data class RunResult(val measures: Map<String, Measure>, val errors: Map<String, String>)

    /** Carpetas que son un dataset parquet (con .parquet dentro, o en subcarpetas clave=valor). */
    fun listDatasets(root: File): List<File> {
        val out = ArrayList<File>()

        fun isDataset(d: File): Boolean {
            val kids = d.listFiles() ?: return false
            return kids.any { it.isFile && it.name.endsWith(".parquet") } ||
                    kids.any { it.isDirectory && it.name.contains("=") && isDataset(it) }
        }

        fun walk(d: File) {
            if (isDataset(d)) {
                out.add(d)
                return
            }
            d.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.forEach { walk(it) }
        }

        walk(root)
        return out
    }

    /**
     * Devuelve la ruta relativa empezando desde `src/...` (sin anteponer el nombre del submódulo
     * cuando el proyecto se abre desde una carpeta raíz multimódulo).
     */
    private fun relative(d: File, base: File): String {
        val a = d.absolutePath.replace("\\", "/")
        val b = base.absolutePath.replace("\\", "/").trimEnd('/') + "/"
        val rel = if (a.startsWith(b)) a.removePrefix(b) else a
        val srcIdx = rel.indexOf("src/")
        return if (srcIdx >= 0) rel.substring(srcIdx) else rel
    }

    private fun connect(): Connection =
        org.duckdb.DuckDBDriver().connect("jdbc:duckdb:", Properties())
            ?: throw IllegalStateException("No se pudo iniciar DuckDB.")

    private fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"

    /** Detecta los nombres de las columnas de partición Hive (carpetas clave=valor) dentro de un dataset. */
    private fun detectHivePartitions(datasetDir: File): List<String> {
        val parts = LinkedHashSet<String>()
        fun walkPart(d: File, depth: Int) {
            if (depth > 6) return
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (k.isDirectory && !k.name.startsWith("_") && !k.name.startsWith(".")) {
                    if (k.name.contains("=")) {
                        val col = k.name.substringBefore("=").trim()
                        if (col.isNotEmpty()) parts.add(col)
                    }
                    walkPart(k, depth + 1)
                }
            }
        }
        walkPart(datasetDir, 0)
        return parts.toList()
    }

    /**
     * Crea la vista en DuckDB leyendo las particiones Hive como VARCHAR para conservar ceros a la izquierda
     * ('01', '02', etc.) igual que Spark/Dataproc; si falla por esquema, hace fallback automático.
     */
    private fun createView(conn: Connection, name: String, absPath: String) {
        val parts = detectHivePartitions(File(absPath))
        if (parts.isNotEmpty()) {
            val hiveTypes = parts.joinToString(", ") { "${lit(it)}: 'VARCHAR'" }
            val typedSql = "CREATE OR REPLACE VIEW ${q(name)} AS SELECT * FROM read_parquet(" +
                    "${lit("$absPath/**/*.parquet")}, hive_partitioning=true, union_by_name=true, hive_types={$hiveTypes})"
            try {
                conn.createStatement().use { st -> st.execute(typedSql) }
                return
            } catch (_: Exception) {
            }
        }
        conn.createStatement().use { st ->
            st.execute(
                "CREATE OR REPLACE VIEW ${q(name)} AS SELECT * FROM read_parquet(" +
                        "${lit("$absPath/**/*.parquet")}, hive_partitioning=true, union_by_name=true)"
            )
        }
    }

    /** Nombre usable directamente en SQL (letras, números y _; no empieza con número). */
    private fun sqlName(raw: String): String {
        val s = raw.replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (s.isEmpty() || s[0].isDigit()) "_$s" else s
    }

    /** Lee TODOS los parquet de una carpeta con DuckDB y devuelve filas y columnas reales. */
    fun inspect(root: File, workDir: File): Inspection = inspect(listOf(root), workDir)

    /**
     * Lee los parquet de VARIAS carpetas (por ejemplo: unit test + inputs) y los junta en una sola lista.
     * Los nombres de tabla son únicos entre carpetas, y un mismo dataset no se repite si una carpeta
     * está dentro de otra o se eligió dos veces.
     */
    fun inspect(roots: List<File>, workDir: File): Inspection {
        val used = HashSet<String>()
        val seen = HashSet<String>()
        val infos = ArrayList<Info>()
        val errors = ArrayList<String>()

        val uniqueRoots = roots.distinctBy { it.absolutePath.replace("\\", "/").trimEnd('/').lowercase() }

        connect().use { conn ->
            for (root in uniqueRoots) {
                val view = LongPaths.resolve(root, true)
                if (!view.isDirectory) throw IllegalStateException("La carpeta de parquet no existe: ${root.path}")
                val dirs = listDatasets(view)
                if (dirs.isEmpty()) throw IllegalStateException("No se encontraron parquet dentro de: ${root.path}")

                val rootRel = relative(root, workDir)
                val viewRoot = view.absolutePath.replace("\\", "/").trimEnd('/')

                for (d in dirs) {
                    val abs = d.absolutePath.replace("\\", "/")
                    if (!seen.add(abs.lowercase())) continue

                    val nm = d.name.substringBefore('.').ifEmpty { root.name }
                    val base = sqlName(nm)
                    var name = base
                    var i = 2
                    while (!used.add(name.lowercase())) {
                        name = "${base}_$i"
                        i++
                    }
                    val sub = abs.removePrefix(viewRoot).trimStart('/')
                    val rawRel = if (sub.isEmpty()) rootRel else "$rootRel/$sub"
                    val srcIdx = rawRel.indexOf("src/")
                    val rel = if (srcIdx >= 0) rawRel.substring(srcIdx) else rawRel

                    try {
                        createView(conn, name, abs)
                        val rows = conn.createStatement().use { st ->
                            st.executeQuery("SELECT count(*) FROM ${q(name)}").use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                        val (cols, types) = conn.createStatement().use { st ->
                            st.executeQuery("SELECT * FROM ${q(name)} LIMIT 0").use { rs ->
                                val md = rs.metaData
                                val names = (1..md.columnCount).map { md.getColumnName(it) }
                                val typeNames = (1..md.columnCount).map { md.getColumnTypeName(it) }
                                Pair(names, typeNames)
                            }
                        }
                        infos.add(Info(name, rel, abs, rows, cols, types))
                    } catch (ex: Exception) {
                        errors.add("${nm}: ${ex.message.orEmpty().replace("\n", " ").take(200)}")
                    }
                }
            }
        }

        if (infos.isEmpty()) {
            throw IllegalStateException("No se pudo leer ningún parquet.\n\n" + errors.joinToString("\n"))
        }
        return Inspection(infos, errors)
    }

    /** Ejecuta las sondas (SQL) sobre los parquet en DuckDB. Cada sonda falla de forma independiente. */
    fun runProbes(probes: List<Probe>, infos: List<Info>): RunResult {
        if (probes.isEmpty()) return RunResult(emptyMap(), emptyMap())

        val measures = LinkedHashMap<String, Measure>()
        val errors = LinkedHashMap<String, String>()

        connect().use { conn ->
            for (info in infos) {
                try {
                    createView(conn, info.name, info.absPath)
                } catch (ex: Exception) {
                }
            }

            for (p in probes) {
                try {
                    val sql = p.code.trim().removeSuffix(";").trim()
                    val head = sql.lowercase()
                    require(head.startsWith("select") || head.startsWith("with")) {
                        "La sonda debe ser un SELECT (o WITH ... SELECT)."
                    }
                    val rows = conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM (\n$sql\n) AS pgat_t").use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                    val cols = conn.createStatement().use { st ->
                        st.executeQuery("SELECT * FROM (\n$sql\n) AS pgat_t LIMIT 0").use { rs ->
                            rs.metaData.columnCount
                        }
                    }
                    measures[p.key] = Measure(rows, cols)
                } catch (ex: Exception) {
                    errors[p.key] = ex.message.orEmpty().replace("\n", " ").take(300)
                }
            }
        }
        return RunResult(measures, errors)
    }
}