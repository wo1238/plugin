package com.example.pgat

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

enum class FieldKind { STRING, SHORT, INT, LONG, FLOAT, DOUBLE, DECIMAL, BOOLEAN, DATE, TIMESTAMP }

data class SchemaField(
    val name: String,
    val rawType: String,
    val kind: FieldKind,
    val nullable: Boolean,
    val length: Int?,
    val precision: Int?,
    val scale: Int?,
    val pk: Boolean
) {
    /** Tipo DuckDB equivalente (el mismo que luego lee Spark). */
    val duckType: String
        get() = when (kind) {
            FieldKind.STRING -> "VARCHAR"
            FieldKind.SHORT -> "SMALLINT"
            FieldKind.INT -> "INTEGER"
            FieldKind.LONG -> "BIGINT"
            FieldKind.FLOAT -> "FLOAT"
            FieldKind.DOUBLE -> "DOUBLE"
            FieldKind.DECIMAL -> "DECIMAL(${precision ?: 18},${scale ?: 2})"
            FieldKind.BOOLEAN -> "BOOLEAN"
            FieldKind.DATE -> "DATE"
            FieldKind.TIMESTAMP -> "TIMESTAMP"
        }
}

data class SchemaDef(
    val name: String,
    val doc: String,
    val partitions: List<String>,
    val fields: List<SchemaField>
)

object SchemaReader {

    /** Lista los archivos .schema de una carpeta (hasta 3 niveles de profundidad). */
    fun listSchemas(folder: File): List<File> {
        if (!folder.isDirectory) return emptyList()
        val skip = setOf("target", ".git", ".idea", "node_modules", "build", "out")
        return try {
            folder.walkTopDown()
                .maxDepth(3)
                .onEnter { it.name !in skip }
                .filter { it.isFile && it.name.endsWith(".schema", ignoreCase = true) }
                .sortedBy { it.absolutePath }
                .toList()
        } catch (ex: Exception) {
            emptyList()
        }
    }

    private val DECIMAL_REGEX = Regex("""decimal\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
    private val LENGTH_REGEX = Regex("""\((\d+)\)""")

    /** El "type" del .schema puede ser "string" o ["string","null"]. Devuelve (tipo, admiteNulos). */
    private fun typeOf(el: JsonElement?): Pair<String, Boolean> {
        if (el == null || el.isJsonNull) return "string" to true
        if (el.isJsonPrimitive) return el.asString to false
        if (el.isJsonArray) {
            var nullable = false
            var type: String? = null
            for (item in el.asJsonArray) {
                if (item.isJsonPrimitive) {
                    val s = item.asString
                    if (s.equals("null", ignoreCase = true)) nullable = true else if (type == null) type = s
                }
            }
            return (type ?: "string") to nullable
        }
        return "string" to true
    }

    private fun str(o: com.google.gson.JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun bool(o: com.google.gson.JsonObject, key: String): Boolean =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    fun read(file: File): SchemaDef {
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")).asJsonObject

        val name = str(root, "name") ?: file.nameWithoutExtension
        val doc = str(root, "doc").orEmpty()
        val partitions = root.get("partitions")?.takeIf { it.isJsonArray }
            ?.asJsonArray?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            ?: emptyList()

        val fields = ArrayList<SchemaField>()
        val arr = root.get("fields")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalStateException("El .schema no tiene la lista \"fields\".")

        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            if (bool(o, "deleted")) continue
            val fname = str(o, "name") ?: continue

            val (rawType, nullable) = typeOf(o.get("type"))
            val t = rawType.trim().lowercase()
            val logical = str(o, "logicalFormat").orEmpty()

            var precision: Int? = null
            var scale: Int? = null
            var length: Int? = null

            val kind: FieldKind = when {
                t.startsWith("decimal") || t.startsWith("numeric") -> {
                    val m = DECIMAL_REGEX.find(t) ?: DECIMAL_REGEX.find(logical)
                    precision = m?.groupValues?.get(1)?.toInt() ?: 18
                    scale = m?.groupValues?.get(2)?.toInt() ?: 2
                    FieldKind.DECIMAL
                }
                t == "short" || t == "smallint" || t == "tinyint" || t == "byte" -> FieldKind.SHORT
                t == "int" || t == "integer" || t == "int32" -> FieldKind.INT
                t == "long" || t == "bigint" || t == "int64" -> FieldKind.LONG
                t == "float" || t == "float32" -> FieldKind.FLOAT
                t == "double" || t == "float64" -> FieldKind.DOUBLE
                t == "boolean" || t == "bool" -> FieldKind.BOOLEAN
                t == "date" -> FieldKind.DATE
                t.startsWith("timestamp") -> FieldKind.TIMESTAMP
                else -> {
                    length = LENGTH_REGEX.find(logical)?.groupValues?.get(1)?.toIntOrNull()
                    FieldKind.STRING
                }
            }

            fields.add(
                SchemaField(
                    name = fname,
                    rawType = rawType,
                    kind = kind,
                    nullable = nullable,
                    length = if (kind == FieldKind.STRING) (length ?: 20) else null,
                    precision = precision,
                    scale = scale,
                    pk = bool(o, "pk")
                )
            )
        }

        if (fields.isEmpty()) throw IllegalStateException("El .schema no tiene campos utilizables.")
        return SchemaDef(name, doc, partitions.filter { p -> fields.any { it.name == p } }, fields)
    }
}