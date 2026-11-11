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

        // Guarda los buffers abiertos para analizar siempre la última versión del código.
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
        val sourceView = LongPaths.resolve(sourceFile, true)
        val fullSource = if (sourceView.isFile) sourceView.readText() else ""

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
                            LongPaths.resolve(testDir, true).mkdirs()
                            val testFile = File(testDir, spec.testFileName)
                            LongPaths.resolve(testFile, true).writeText(result.code)

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

        // ---------------- GETDATA / GENERATE ----------------
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

        // GetData: solo inputs. Generate: unit test + inputs.
        val inputRoot = File(dialog.txtInputPath.text)
        val parquetRoots: List<File> = if (testType == "Generate") {
            listOf(File(dialog.txtParquetPath.text), inputRoot)
        } else {
            listOf(inputRoot)
        }

        val configFile = File(dialog.txtConfigPath.text)
        val configRel = runCatching { configFile.relativeTo(File(basePath)).path }
            .getOrDefault(configFile.path)
            .replace("\\", "/")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generando test de $testType...", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Leyendo parquet de muestra con DuckDB..."
                        val inspection = ParquetProbe.inspect(parquetRoots, File(basePath))

                        val out = MeasuredTestBuilder.build(
                            testType = testType,
                            pkg = pkg,
                            spec = spec,
                            code = code,
                            fullSource = fullSource,
                            sourceFile = sourceFile,
                            basePath = File(basePath),
                            configRel = configRel,
                            configText = LongPaths.resolve(configFile, true).readText(),
                            infos = inspection.infos,
                            focus = methodName,
                            progress = { msg -> indicator.text = msg },
                            askAi = { p -> AiClient.cleanCode(AiClient.ask(p)) }
                        )

                        // Si la clase quedó incompleta, se guarda como borrador para no dejar un .scala roto.
                        val fileName = if (out.complete) spec.testFileName else spec.testFileName + ".draft"

                        indicator.text = "Guardando test..."
                        val testDir = File(testOutputPath)
                        LongPaths.resolve(testDir, true).mkdirs()
                        val testFile = File(testDir, fileName)
                        LongPaths.resolve(testFile, true).writeText(out.code)

                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testFile)?.let { vf ->
                            ApplicationManager.getApplication().invokeLater {
                                FileEditorManager.getInstance(project).openFile(vf, true)
                            }
                        }

                        val sb = StringBuilder()
                        sb.append("$fileName\n\n")
                        if (!out.complete) {
                            sb.append("INCOMPLETO: guardado como borrador (.draft). Revisa la sección REVISAR.\n\n")
                        }
                        sb.append("Medido sobre tus parquet:\n")
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