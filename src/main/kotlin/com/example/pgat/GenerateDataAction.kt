package com.example.pgat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class GenerateDataAction : AnAction("Generar Data de Prueba (Parquet)") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        // Si se hizo clic derecho sobre un archivo, se propone como esquema
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val initialSchema = selected?.takeIf { !it.isDirectory }?.path ?: ""

        val dialog = GenerateDataDialog(basePath, initialSchema)
        if (!dialog.showAndGet()) return

        val isModify = dialog.rbModifyExisting.isSelected
        val records = dialog.txtRecords.text.trim()
        val partitions = dialog.comboPartitions.selectedItem.toString()
        val inputPath = dialog.txtInputPath.text.replace("\\", "/")
        val outputPath = dialog.txtOutputPath.text.replace("\\", "/")
        val edgeCases = dialog.checkEdgeCases.isSelected
        val schemaContent = File(dialog.txtSchemaPath.text).readText()

        val partitionInstruction = if (partitions == "Sin Particiones") {
            "No particiones la salida (no uses partitionBy ni repartition)."
        } else {
            "Usa repartition($partitions) antes de escribir."
        }

        val operation = if (isModify) {
            listOf(
                "1. LEE el parquet existente en '$inputPath'.",
                "2. Modifícalo para que cumpla el esquema proporcionado.",
                "3. Guárdalo (modo overwrite) en '$outputPath'.",
                "4. $partitionInstruction"
            )
        } else {
            listOf(
                "1. GENERA $records registros mockeados que respeten exactamente el esquema.",
                if (edgeCases) {
                    "2. Incluye casos de borde: nulos, strings vacíos y valores extremos."
                } else {
                    "2. Usa solo valores válidos y realistas."
                },
                "3. Guárdalo en formato parquet (modo overwrite) en '$outputPath'.",
                "4. $partitionInstruction"
            )
        }.joinToString("\n")

        val prompt = listOf(
            "Eres un experto en Scala y Apache Spark. NO HABLES. NO EXPLIQUES. Devuelve SOLO un script Scala válido, sin bloques markdown.",
            "",
            "Esquema:",
            schemaContent,
            "",
            "Tarea:",
            operation,
            "",
            "Importa 'org.apache.spark.sql.functions._' y crea la sesión con:",
            "val spark = org.apache.spark.sql.SparkSession.builder().master(\"local[*]\").getOrCreate()"
        ).joinToString("\n")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generando data de prueba...", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Consultando a la IA..."
                        val script = AiClient.cleanCode(AiClient.ask(prompt))
                        if (script.isEmpty()) {
                            Notifier.error(project, "Generar Data", "La IA devolvió una respuesta vacía.")
                            return
                        }

                        indicator.text = "Ejecutando script Scala/Spark..."
                        val (ok, log) = ScalaRunner.run(script, File(basePath))

                        LocalFileSystem.getInstance()
                            .refreshAndFindFileByIoFile(File(outputPath))
                            ?.refresh(true, true)

                        if (ok) {
                            Notifier.info(project, "Generar Data", "Parquet generado en:\n$outputPath")
                        } else {
                            Notifier.error(project, "Generar Data", "Falló la ejecución del script:\n\n$log")
                        }
                    } catch (ex: Exception) {
                        Notifier.error(project, "Generar Data", "Error: ${ex.message}")
                    }
                }
            }
        )
    }
}
