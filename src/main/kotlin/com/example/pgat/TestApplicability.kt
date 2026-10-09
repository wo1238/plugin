package com.example.pgat

/** Revisión local rápida: ¿el código tiene algo sobre lo que se pueda hacer este tipo de test? */
object TestApplicability {

    fun isApplicable(testType: String, code: String): Boolean = when (testType) {
        "Generate" -> Regex("""def\s+\w+[^=\n]*:\s*(DataFrame|Dataset\[)""").containsMatchIn(code)
        "GetData" -> Regex("""def\s+\w+[^=\n]*Map\[\s*String\s*,\s*DataFrame\s*\]""").containsMatchIn(code)
        else -> Regex("""object\s+\w+""").containsMatchIn(code) &&
                Regex("""val\s+[A-Z][A-Z0-9_]*\b""").containsMatchIn(code)
    }

    fun criteria(testType: String): String = when (testType) {
        "Generate" -> "un método (normalmente 'apply') que devuelva un DataFrame o Dataset"
        "GetData" -> "un método que devuelva un Map[String, DataFrame] con los DataFrames de entrada"
        else -> "un object con constantes (val en MAYÚSCULAS) de configuración"
    }
}