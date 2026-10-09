package com.example.pgat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.langchain4j.model.anthropic.AnthropicChatModel
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Cliente de IA compartido por las dos funcionalidades. */
object AiClient {
    // Debe terminar en /v1/ (con la barra final)
    private const val BASE_URL = "https://pgat-proxy.camilo-giraldo.workers.dev/v1/"
    // El MISMO valor que pusiste en PROXY_SECRET del Worker (usa uno nuevo, no el anterior)
    private const val PROXY_SECRET = "a8f3k2x9q7m1z5"
    // El Worker fuerza este modelo de todos modos; aquí solo es referencia
    private const val MODEL = "claude-haiku-5-5"


    fun ask(prompt: String): String {
        val model = AnthropicChatModel.builder()
            .baseUrl(BASE_URL)
            .apiKey(PROXY_SECRET)
            .modelName(MODEL)
            .maxTokens(4096)
            .timeout(Duration.ofSeconds(180))
            .build()
        return model.generate(prompt).trim()
    }

    /** Quita las marcas de bloque de código (``` + lenguaje) que a veces añade la IA. */
    fun cleanCode(raw: String?): String =
        raw.orEmpty().replace(Regex("`{3}[a-zA-Z]*"), "").trim()
}

/** Mensajes al usuario desde hilos en segundo plano. */
object Notifier {
    fun info(project: Project, title: String, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, title)
        }
    }

    fun error(project: Project, title: String, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, title)
        }
    }
}

/**
 * Ejecuta un script Scala temporal y devuelve (éxito, log).
 *
 * Para que el script encuentre Spark busca el classpath en este orden:
 *  1. Variable de entorno PGAT_SPARK_CP (ej: C:\spark\jars\*)
 *  2. SPARK_HOME\jars\*
 *  3. Si no hay ninguno, antepone directivas de scala-cli para descargar Spark.
 */
object ScalaRunner {

    private const val SPARK_SCALA_BINARY = "2.12"
    private const val SPARK_VERSION = "3.5.1"

    private fun sparkClasspath(): String? {
        val custom = System.getenv("PGAT_SPARK_CP")
        if (!custom.isNullOrBlank()) return custom

        val home = System.getenv("SPARK_HOME")
        if (!home.isNullOrBlank()) {
            val jars = File(home, "jars")
            if (jars.isDirectory) return jars.absolutePath + File.separator + "*"
        }
        return null
    }

    fun run(code: String, workDir: File, tailChars: Int = 1500, timeoutSec: Long = 120): Pair<Boolean, String> {
        val script = File(workDir, "TempSparkProcessor.scala")
        val log = File.createTempFile("pgat-scala", ".log")
        try {
            val classpath = sparkClasspath()

            // Sin classpath local: directivas de scala-cli (si el 'scala' no es scala-cli, son solo comentarios).
            val finalCode = if (classpath == null) {
                "//> using scala $SPARK_SCALA_BINARY\n" +
                        "//> using dep org.apache.spark::spark-sql:$SPARK_VERSION\n" +
                        code
            } else {
                code
            }
            script.writeText(finalCode)

            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val args = ArrayList<String>()
            if (isWindows) {
                args.add("cmd")
                args.add("/c")
            }
            args.add("scala")
            if (classpath != null) {
                args.add("-cp")
                args.add(classpath)
            }
            args.add(script.absolutePath)

            val process = ProcessBuilder(args)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()

            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false to "Tiempo de espera agotado ($timeoutSec s).\n" + tail(log, tailChars)
            }

            var output = tail(log, tailChars)
            val ok = process.exitValue() == 0
            if (!ok && output.contains("is not a member of package org")) {
                output += "\n\nPISTA: el comando 'scala' no encuentra Spark. Define la variable de entorno " +
                        "SPARK_HOME (carpeta de Spark con 'jars') o PGAT_SPARK_CP (ej: C:\\spark\\jars\\*) " +
                        "y reinicia IntelliJ."
            }
            return ok to output
        } catch (ex: Exception) {
            return false to "No se pudo ejecutar 'scala': ${ex.message}"
        } finally {
            script.delete()
            log.delete()
        }
    }

    private fun tail(file: File, n: Int): String = file.readText().takeLast(n)
}