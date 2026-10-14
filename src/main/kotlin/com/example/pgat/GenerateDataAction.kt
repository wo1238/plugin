package com.example.pgat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class GenerateDataAction : AnAction("Generar Data de Prueba (Parquet)") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val initialSchema = selected?.path ?: ""

        val dialog = GenerateDataDialog(basePath, initialSchema)
        if (!dialog.showAndGet()) return

        // ---------------- MODIFICAR PARQUET EXISTENTE ----------------
        if (dialog.isModifyMode) {
            val inputDir = File(dialog.txtInputPath.text.trim())
            val column = dialog.selectedModColumn
            val oldValue: String? = dialog.selectedOldValue
            val newValue = dialog.txtModNew.text
            val oldShown = if (oldValue == null) "(nulo)" else "'$oldValue'"

            val answer = Messages.showYesNoDialog(
                project,
                "Se va a reescribir el parquet:\n${inputDir.path}\n\n" +
                        "Cambio: $column = $oldShown  ->  '$newValue'\n\n¿Continuar?",
                "Modificar parquet",
                Messages.getQuestionIcon()
            )
            if (answer != Messages.YES) return

            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Modificando parquet...", false) {
                    override fun run(indicator: ProgressIndicator) {
                        try {
                            indicator.text = "Leyendo y reescribiendo con DuckDB..."
                            val r = DataGenerator.modify(inputDir, column, oldValue, newValue)
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(inputDir)?.refresh(true, true)

                            val parts = if (r.partitionCols.isEmpty()) "sin particiones"
                            else "particiones: " + r.partitionCols.joinToString(", ")
                            Notifier.info(
                                project, "Modificar parquet",
                                "Listo: ${r.changedRows} de ${r.totalRows} filas cambiadas en '$column'.\n" +
                                        "Se mantuvo la estructura ($parts).\n${r.outputDir.path}"
                            )
                        } catch (ex: Exception) {
                            Notifier.error(project, "Modificar parquet", "Error: ${ex.message}")
                        }
                    }
                }
            )
            return
        }

        // ---------------- GENERAR DATA NUEVA ----------------
        val schema = dialog.schema ?: return
        val specs = dialog.collectSpecs()
        val records = dialog.txtRecords.text.toIntOrNull() ?: 100
        val outputRoot = File(dialog.txtOutputPath.text.trim())
        val target = File(outputRoot, schema.name)

        if (target.exists()) {
            val answer = Messages.showYesNoDialog(
                project,
                "La carpeta ya existe y se va a reemplazar:\n${target.path}\n\n¿Continuar?",
                "Generar data de prueba",
                Messages.getWarningIcon()
            )
            if (answer != Messages.YES) return
        }

        val request = GenerateRequest(
            schema = schema,
            specs = specs,
            records = records,
            edgeCases = dialog.checkEdgeCases.isSelected,
            storage = dialog.partitionStorage,
            outputRoot = outputRoot
        )
        val usesAi = specs.any { it.mode == FieldMode.AI && !it.partition }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generando data de prueba...", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = if (usesAi) "Pidiendo valores a la IA y generando parquet..."
                        else "Generando parquet con DuckDB..."

                        val r = DataGenerator.generate(request) { p -> AiClient.cleanCode(AiClient.ask(p)) }

                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(r.outputDir)?.refresh(true, true)

                        val sb = StringBuilder()
                        sb.append("${schema.name}\n\n")
                        sb.append("- ${r.rows} registros, ${r.files} archivo(s) parquet\n")
                        sb.append(
                            if (r.partitionCols.isEmpty()) "- Sin carpetas de partición\n"
                            else "- Particiones en carpetas: ${r.partitionCols.joinToString(", ")}\n"
                        )
                        sb.append("- Ruta: ${r.outputDir.path}\n")
                        if (r.warnings.isNotEmpty()) {
                            sb.append("\nRevisar:\n")
                            r.warnings.forEach { sb.append("- $it\n") }
                        }
                        Notifier.info(project, "Generar data de prueba", sb.toString())
                    } catch (ex: Exception) {
                        Notifier.error(project, "Generar data de prueba", "Error: ${ex.message}")
                    }
                }
            }
        )
    }
}