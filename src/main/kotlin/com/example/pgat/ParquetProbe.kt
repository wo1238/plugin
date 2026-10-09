package com.example.pgat

import java.io.File

/**
 * Todo lo que toca Spark: leer los parquet de muestra y ejecutar las "sondas"
 * (código Spark que reproduce un método) para medir filas y columnas reales.
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

    /** Lee TODOS los parquet de la carpeta con Spark y devuelve filas y columnas reales. */
    fun inspect(root: File, workDir: File): Inspection {
        if (!root.isDirectory) throw IllegalStateException("La carpeta de parquet no existe: ${root.path}")
        val dirs = listDatasets(root)
        if (dirs.isEmpty()) throw IllegalStateException("No se encontraron parquet dentro de: ${root.path}")

        val used = HashSet<String>()
        val named = dirs.map { d ->
            var name = d.name
            var i = 2
            while (!used.add(name)) {
                name = "${d.name}_$i"
                i++
            }
            Triple(name, d, d.absolutePath.replace("\\", "/"))
        }

        val paths = named.joinToString(", ") { "\"${it.third}\"" }
        val script = """
            import org.apache.spark.sql.SparkSession
            val spark = SparkSession.builder().master("local[*]").getOrCreate()
            spark.sparkContext.setLogLevel("ERROR")
            val paths = Seq($paths)
            paths.foreach { p =>
              try {
                val df = spark.read.parquet(p)
                println("PGAT_INFO|" + p + "|" + df.count() + "|" + df.columns.mkString(","))
              } catch { case e: Throwable => println("PGAT_ERR|" + p + "|" + String.valueOf(e.getMessage).replace("\n", " ")) }
            }
        """.trimIndent()

        val (_, log) = ScalaRunner.run(script, workDir, 60000, 300)

        val infos = ArrayList<Info>()
        val errors = ArrayList<String>()
        for (line in log.lines()) {
            val t = line.trim()
            if (t.startsWith("PGAT_INFO|")) {
                val p = t.split("|", limit = 4)
                val entry = named.firstOrNull { it.third == p.getOrNull(1) } ?: continue
                val rows = p.getOrNull(2)?.toLongOrNull() ?: continue
                val cols = p.getOrNull(3).orEmpty().split(",").filter { it.isNotBlank() }
                infos.add(Info(entry.first, relative(entry.second, workDir), entry.third, rows, cols))
            } else if (t.startsWith("PGAT_ERR|")) {
                errors.add(t.removePrefix("PGAT_ERR|").take(200))
            }
        }

        if (infos.isEmpty()) {
            throw IllegalStateException("No se pudo leer ningún parquet con Spark.\n\n" + log.takeLast(1500))
        }
        return Inspection(infos, errors)
    }

    /**
     * Pega en el script las definiciones de los objects de constantes (Parametry) para que
     * las sondas usen los valores EXACTOS sin que la IA los copie.
     */
    fun companionPreamble(files: List<File>): String {
        val sb = StringBuilder()
        val objects = ArrayList<String>()
        val objRegex = Regex("""(?m)^\s*object\s+(\w+)""")
        val classRegex = Regex("""(?m)^\s*(case\s+)?class\s""")
        val qualifier = Regex("""(private|protected)\[[^\]]*\]""")

        for (f in files) {
            val text = f.readText()
            if (!objRegex.containsMatchIn(text) || classRegex.containsMatchIn(text)) continue
            val kept = text.lines().filter { l ->
                val t = l.trim()
                !t.startsWith("package ") &&
                        (!t.startsWith("import ") ||
                                t.startsWith("import org.apache.") ||
                                t.startsWith("import scala.") ||
                                t.startsWith("import java."))
            }
            sb.appendLine(qualifier.replace(kept.joinToString("\n"), ""))
            objRegex.findAll(text).forEach { objects.add(it.groupValues[1]) }
        }
        objects.distinct().forEach { sb.appendLine("import $it._") }
        return sb.toString()
    }

    private fun probeScript(probes: List<Probe>, preamble: String, infos: List<Info>): String {
        val pq = infos.joinToString(", ") { "\"${it.name}\" -> \"${it.absPath}\"" }
        val sb = StringBuilder()
        sb.appendLine("import org.apache.spark.sql.{DataFrame, SparkSession}")
        sb.appendLine("import org.apache.spark.sql.functions._")
        sb.appendLine("val spark = SparkSession.builder().master(\"local[*]\").getOrCreate()")
        sb.appendLine("spark.sparkContext.setLogLevel(\"ERROR\")")
        sb.appendLine("import spark.implicits._")
        sb.appendLine("val PQ: Map[String, String] = Map($pq)")
        sb.appendLine(preamble)
        for (p in probes) {
            sb.appendLine("locally {")
            sb.appendLine("  try {")
            sb.appendLine(p.code)
            sb.appendLine("    println(\"PGAT|${p.key}|\" + result.count() + \"|\" + result.columns.length)")
            sb.appendLine("  } catch { case e: Throwable => println(\"PGAT_ERR|${p.key}|\" + String.valueOf(e.getMessage).replace(\"\\n\", \" \")) }")
            sb.appendLine("}")
        }
        return sb.toString()
    }

    private fun collect(log: String, m: MutableMap<String, Measure>, e: MutableMap<String, String>) {
        for (line in log.lines()) {
            val t = line.trim()
            if (t.startsWith("PGAT|")) {
                val p = t.split("|")
                val rows = p.getOrNull(2)?.toLongOrNull()
                val cols = p.getOrNull(3)?.toIntOrNull()
                if (p.size >= 4 && rows != null && cols != null) m[p[1]] = Measure(rows, cols)
            } else if (t.startsWith("PGAT_ERR|")) {
                val p = t.split("|", limit = 3)
                if (p.size == 3) e[p[1]] = p[2].take(300)
            }
        }
    }

    /** Ejecuta las sondas. Si el script completo no compila, reintenta cada sonda sola para aislar la defectuosa. */
    fun runProbes(probes: List<Probe>, preamble: String, infos: List<Info>, workDir: File): RunResult {
        if (probes.isEmpty()) return RunResult(emptyMap(), emptyMap())

        val measures = LinkedHashMap<String, Measure>()
        val errors = LinkedHashMap<String, String>()

        val (_, log) = ScalaRunner.run(probeScript(probes, preamble, infos), workDir, 40000, 300)
        collect(log, measures, errors)

        val silent = probes.filter { it.key !in measures && it.key !in errors }
        if (silent.isNotEmpty()) {
            if (probes.size == 1) {
                errors[silent[0].key] = log.trim().takeLast(400)
            } else {
                for (p in silent) {
                    val (_, l2) = ScalaRunner.run(probeScript(listOf(p), preamble, infos), workDir, 40000, 300)
                    collect(l2, measures, errors)
                    if (p.key !in measures && p.key !in errors) errors[p.key] = l2.trim().takeLast(400)
                }
            }
        }
        return RunResult(measures, errors)
    }
}