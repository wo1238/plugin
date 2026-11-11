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

//Se ejecuta la clase cuando en el menu principal que sale en plugin xml le damos clcik a
//Generar Data de Prueba (Parquet)
class GenerateDataAction : AnAction("Generar Data de Prueba (Parquet)")
{
    // declaramos funcion la cual se va a ejecutar siempre que demos click derecho a la accion de arriba
    override fun actionPerformed(e: AnActionEvent) {
        //aca se obtiene el proyecto en el cual estamos trabajando y el plugin lo guarda
        //si no hay devuelve nulo
        val project = e.project ?: return
        //obtiene la carpeta raiz del proyecto si no la hay devuelve nulo
        val basePath = project.basePath ?: return
        //obtiene la carpeta especifica del proyecto si no la hay devuelve nulo
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        //mira si hay un eseuqma dentroy lo propone como el esquema inicial
        val initialSchema = selected?.path ?: ""
        //llama una funcion de generate datadialog e imprime en el cuadro de dialogo la ruta, si la tomo
        val dialog = GenerateDataDialog(basePath, initialSchema)
        if (!dialog.showAndGet()) return

        //si la opcion de dialog no es crear si no modificar un parquet entra aca
        if (dialog.isModifyMode) {
            //lee la carpeta, la cual nosotros seleccionamos
            val inputDir = File(dialog.txtInputPath.text.trim())
            //toma la columna la cual queremos cambiar
            val column = dialog.selectedModColumn
            //toma el valor viejo el cual queremos cambiar
            val oldValue: String? = dialog.selectedOldValue
            //toma el valor nuevo el cual queremos poner
            val newValue = dialog.txtModNew.text
            //linea para mostrarle a el usuario un nulo y que no quede solo vacio
            val oldShown = if (oldValue == null) "(nulo)" else "'$oldValue'"

            // esto arma una aletar para que el usuario diga si quiere o no que haga el cambio el plugin, si si es lo que espera
            val answer = Messages.showYesNoDialog(
                project,
                "Se va a reescribir el parquet:\n${inputDir.path}\n\n" +
                        "Cambio: $column = $oldShown  ->  '$newValue'\n\n¿Continuar?",
                "Modificar parquet",
                Messages.getQuestionIcon()
            )
            if (answer != Messages.YES) return

            //generamos en segundo pñano el parquet para ue no se congele el ide, es lo que se ve abajo, mientras se va ejecutando cada proceso
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

        //obtiene el eseqema para data la cual no es modificada
        val schema = dialog.schema ?: return
        //recopila el tipo de dato y la opcion por la cual lo vamos a generar
        val specs = dialog.collectSpecs()
        // los registros que queremos crear, la cantidad
        val records = dialog.txtRecords.text.toIntOrNull() ?: 100
        // donde vamos a escribir la data
        val outputRoot = File(dialog.txtOutputPath.text.trim())
        //junta la carpeta de salida mas el nombre de el esuquema para crear la data ahi dentro
        val target = File(outputRoot, schema.name)
        //si la carpeta existe, pregunta si queremos sobre escribir (se revisa con ruta corta por si es muy larga)
        if (LongPaths.resolve(target, true).exists()) {
            val answer = Messages.showYesNoDialog(
                project,
                "La carpeta ya existe y se va a reemplazar:\n${target.path}\n\n¿Continuar?",
                "Generar data de prueba",
                Messages.getWarningIcon()
            )
            if (answer != Messages.YES) return
        }

        //junta los datos en un solo objeto para pasarlos mas adelante

        val request = GenerateRequest(
            schema = schema,
            specs = specs,
            records = records,
            edgeCases = dialog.checkEdgeCases.isSelected,
            storage = dialog.partitionStorage,
            outputRoot = outputRoot
        )

        //revisa si el usuario seleeciono un campo con que se use ia o no para ese campo
        val usesAi = specs.any { it.mode == FieldMode.AI && !it.partition }


        //ejecutamos en segundo plano y genermos el parquet nuevo
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generando data de prueba...", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = if (usesAi) "Pidiendo valores a la IA y generando parquet..."
                        else "Generando parquet con DuckDB..."

                        //si se movio la opcion de que algun campo necestia ia, esta funcion es la encargada de masndar el promt y el promt alli
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