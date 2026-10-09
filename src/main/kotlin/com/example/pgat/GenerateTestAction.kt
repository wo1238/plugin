package com.example.pgat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class GenerateTestAction : AnAction("Generar Test Unitario (ScalaTest)") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        // CRÍTICO: si el archivo fuente tiene cambios sin guardar en el editor,
        // File(...).readText() más abajo lee la versión VIEJA desde disco.
        // Esto fuerza a IntelliJ a volcar todos los buffers abiertos a disco
        // antes de leer nada, para que SIEMPRE se analice lo último que
        // escribiste (aunque no hayas hecho Ctrl+S).
        FileDocumentManager.getInstance().saveAllDocuments()

        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val initialSource = selected?.takeIf { !it.isDirectory }?.path ?: ""
        val selectedText = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val hasSelection = !selectedText.isNullOrBlank()

        val dialog = GenerateTestDialog(basePath, initialSource, hasSelection)
        if (!dialog.showAndGet()) return

        val testType = when {
            dialog.rbTestGetData.isSelected -> "GetData"
            dialog.rbTestParametry.isSelected -> "Parametry"
            else -> "Generate"
        }

        val sourceFile = File(dialog.txtSourcePath.text)
        val fullSource = if (sourceFile.isFile) sourceFile.readText() else ""

        // Parametry siempre analiza el archivo COMPLETO, nunca solo la selección.
        val code = if (testType != "Parametry" && dialog.chkUseSelection.isSelected && hasSelection) {
            selectedText!!
        } else {
            fullSource
        }

        val pkg = Regex("""package\s+([\w.]+)""").find(fullSource)?.groupValues?.get(1) ?: "(no detectado)"
        val methodName = dialog.txtMethod.text.trim()
        val testOutputPath = dialog.txtTestOutputPath.text.replace("\\", "/")

        val spec = TestPromptBuilder.resolveTestSpec(sourceFile, fullSource)

        // ---------------- PARAMETRY ----------------
        if (testType == "Parametry") {

            if (!TestApplicability.isApplicable(testType, code)) {
                val answer = Messages.showYesNoDialog(
                    project,
                    "No se encontró en el código ${TestApplicability.criteria(testType)}.\n\n" +
                            "¿Quieres generar el test de todos modos? (puede quedar vacío)",
                    "Test de $testType",
                    Messages.getQuestionIcon()
                )
                if (answer != Messages.YES) return
            }

            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Generando test de Parametry...", false) {
                    override fun run(indicator: ProgressIndicator) {
                        try {
                            indicator.text = "Generando asserts (local + IA solo en casos ambiguos)..."
                            val result = TestPromptBuilder.buildParametryTestCode(
                                pkg, spec, fullSource
                            ) { p -> AiClient.cleanCode(AiClient.ask(p)) }

                            indicator.text = "Guardando test..."
                            val testDir = File(testOutputPath)
                            testDir.mkdirs()
                            val testFile = File(testDir, spec.testFileName)
                            testFile.writeText(result.code)

                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testFile)?.let { vf ->
                                ApplicationManager.getApplication().invokeLater {
                                    FileEditorManager.getInstance(project).openFile(vf, true)
                                }
                            }

                            val s = result.stats
                            val localCount = s.total - s.aiResolved - s.aiFallback
                            val extra = if (s.aiFallback > 0)
                                "\n${s.aiFallback} quedaron con // TODO: revisar manualmente."
                            else ""
                            Notifier.info(
                                project, "Test de Parametry",
                                "${spec.testFileName}: ${s.total} constante(s)\n" +
                                        "- $localCount generadas localmente\n" +
                                        "- ${s.aiResolved} resueltas con IA (casos ambiguos)$extra"
                            )
                        } catch (ex: Exception) {
                            Notifier.error(project, "Test de Parametry", "Error: ${ex.message}")
                        }
                    }
                }
            )
            return
        }

        // ---------------- GETDATA / GENERATE (con números medidos) ----------------
        if (!TestApplicability.isApplicable(testType, code)) {
            val answer = Messages.showYesNoDialog(
                project,
                "No se encontró en el código ${TestApplicability.criteria(testType)}.\n\n" +
                        "¿Quieres continuar de todos modos?",
                "Test de $testType",
                Messages.getQuestionIcon()
            )
            if (answer != Messages.YES) return
        }

        val parquetRoot = File(dialog.txtParquetPath.text)
        val configFile = File(dialog.txtConfigPath.text)
        val configRel = runCatching { configFile.relativeTo(File(basePath)).path }
            .getOrDefault(configFile.path)
            .replace("\\", "/")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generando test de $testType...", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Leyendo parquet de muestra con Spark..."
                        val inspection = ParquetProbe.inspect(parquetRoot, File(basePath))

                        val out = MeasuredTestBuilder.build(
                            testType = testType,
                            pkg = pkg,
                            spec = spec,
                            code = code,
                            fullSource = fullSource,
                            sourceFile = sourceFile,
                            basePath = File(basePath),
                            configRel = configRel,
                            configText = configFile.readText().take(8000),
                            infos = inspection.infos,
                            focus = methodName,
                            progress = { msg -> indicator.text = msg },
                            askAi = { p -> AiClient.cleanCode(AiClient.ask(p)) }
                        )

                        indicator.text = "Guardando test..."
                        val testDir = File(testOutputPath)
                        testDir.mkdirs()
                        val testFile = File(testDir, spec.testFileName)
                        testFile.writeText(out.code)

                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testFile)?.let { vf ->
                            ApplicationManager.getApplication().invokeLater {
                                FileEditorManager.getInstance(project).openFile(vf, true)
                            }
                        }

                        val sb = StringBuilder()
                        sb.append("${spec.testFileName}\n\nMedido con Spark sobre tus parquet:\n")
                        if (out.measured.isEmpty()) sb.append("- (ninguna medición)\n")
                        out.measured.forEach { sb.append("- $it\n") }

                        val problems = out.problems + inspection.errors.map { "No se pudo leer un parquet: $it" }
                        if (problems.isNotEmpty()) {
                            sb.append("\nRevisar:\n")
                            problems.forEach { sb.append("- $it\n") }
                        }
                        Notifier.info(project, "Test de $testType", sb.toString())
                    } catch (ex: Exception) {
                        Notifier.error(project, "Test de $testType", "Error: ${ex.message}")
                    }
                }
            }
        )
    }
}