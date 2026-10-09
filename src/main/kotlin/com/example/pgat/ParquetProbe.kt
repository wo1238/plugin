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
        val columns: List<String>
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

    private fun relative(d: File, base: File): String {
        val a = d.absolutePath.replace("\\", "/")
        val b = base.absolutePath.replace("\\", "/").trimEnd('/') + "/"
        return if (a.startsWith(b)) a.removePrefix(b) else a
    }

    private fun connect(): Connection =
        org.duckdb.DuckDBDriver().connect("jdbc:duckdb:", Properties())
            ?: throw IllegalStateException("No se pudo iniciar DuckDB.")

    private fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"

    private fun createView(conn: Connection, name: String, absPath: String) {
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

    /** Lee TODOS los parquet de la carpeta con DuckDB y devuelve filas y columnas reales. */
    fun inspect(root: File, workDir: File): Inspection {
        if (!root.isDirectory) throw IllegalStateException("La carpeta de parquet no existe: ${root.path}")
        val dirs = listDatasets(root)
        if (dirs.isEmpty()) throw IllegalStateException("No se encontraron parquet dentro de: ${root.path}")

        val used = HashSet<String>()
        val infos = ArrayList<Info>()
        val errors = ArrayList<String>()

        connect().use { conn ->
            for (d in dirs) {
                val base = sqlName(d.name)
                var name = base
                var i = 2
                while (!used.add(name.lowercase())) {
                    name = "${base}_$i"
                    i++
                }
                val abs = d.absolutePath.replace("\\", "/")
                try {
                    createView(conn, name, abs)
                    val rows = conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM ${q(name)}").use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                    val cols = conn.createStatement().use { st ->
                        st.executeQuery("SELECT * FROM ${q(name)} LIMIT 0").use { rs ->
                            val md = rs.metaData
                            (1..md.columnCount).map { md.getColumnName(it) }
                        }
                    }
                    infos.add(Info(name, relative(d, workDir), abs, rows, cols))
                } catch (ex: Exception) {
                    errors.add("${d.name}: ${ex.message.orEmpty().replace("\n", " ").take(200)}")
                }
            }
        }

        if (infos.isEmpty()) {
            throw IllegalStateException("No se pudo leer ningún parquet.\n\n" + errors.joinToString("\n"))
        }
        return Inspection(infos, errors)
    }

    /** Ejecuta las sondas (SQL) sobre los parquet. Cada sonda falla de forma independiente. */
    fun runProbes(probes: List<Probe>, infos: List<Info>): RunResult {
        if (probes.isEmpty()) return RunResult(emptyMap(), emptyMap())

        val measures = LinkedHashMap<String, Measure>()
        val errors = LinkedHashMap<String, String>()

        connect().use { conn ->
            for (info in infos) {
                try {
                    createView(conn, info.name, info.absPath)
                } catch (ex: Exception) {
                    // el parquet ya falló en inspect; las sondas que lo usen reportarán el error
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