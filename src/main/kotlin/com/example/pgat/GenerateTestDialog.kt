package com.example.pgat

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JRadioButton
import javax.swing.JTextField

class GenerateTestDialog(
    basePath: String,
    initialSourcePath: String,
    hasSelection: Boolean
) : DialogWrapper(true) {

    val rbTestGenerate = JRadioButton("Generate: clase con método apply que devuelve un DataFrame", true)
    val rbTestGetData = JRadioButton("GetData: clase con métodos que leen/preparan DataFrames")
    val rbTestParametry = JRadioButton("Parametry: object con constantes (val)")

    val txtSourcePath = TextFieldWithBrowseButton()
    val txtParquetPath = TextFieldWithBrowseButton()
    val txtConfigPath = TextFieldWithBrowseButton()
    val chkUseSelection = JCheckBox("Usar solo el texto seleccionado en el editor", hasSelection)
    val txtMethod = JTextField()
    val txtTestOutputPath = TextFieldWithBrowseButton()

    init {
        title = "Generar Test Unitario (ScalaTest)"

        ButtonGroup().apply {
            add(rbTestGenerate)
            add(rbTestGetData)
            add(rbTestParametry)
        }

        txtSourcePath.addBrowseFolderListener(
            "Seleccionar archivo Scala a probar", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
        txtParquetPath.addBrowseFolderListener(
            "Seleccionar carpeta con los parquet de muestra", null, null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        txtConfigPath.addBrowseFolderListener(
            "Seleccionar archivo .conf del test", null, null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
        txtTestOutputPath.addBrowseFolderListener(
            "Seleccionar directorio de salida de tests", null, null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        txtSourcePath.text = initialSourcePath

        val uniTest = File("$basePath/src/test/resources/UniTest")
        txtParquetPath.text = if (uniTest.isDirectory) uniTest.path else "$basePath/src/test/resources"

        txtConfigPath.text = File("$basePath/src/test/resources/config")
            .listFiles { f -> f.isFile && f.extension == "conf" }
            ?.minByOrNull { it.name }
            ?.path
            ?: ""

        txtTestOutputPath.text = "$basePath/src/test/scala/com/bbva/co/csan/data/"
        chkUseSelection.isEnabled = hasSelection

        fun updateState() {
            val needsData = !rbTestParametry.isSelected
            txtParquetPath.isEnabled = needsData
            txtConfigPath.isEnabled = needsData
        }
        rbTestGenerate.addActionListener { updateState() }
        rbTestGetData.addActionListener { updateState() }
        rbTestParametry.addActionListener { updateState() }
        updateState()

        init()
    }

    override fun doValidate(): ValidationInfo? {
        if (!chkUseSelection.isSelected && !File(txtSourcePath.text).isFile) {
            return ValidationInfo("Selecciona el archivo Scala que quieres probar.", txtSourcePath)
        }
        if (!rbTestParametry.isSelected) {
            if (!File(txtParquetPath.text).isDirectory) {
                return ValidationInfo("Selecciona la carpeta con los parquet de muestra.", txtParquetPath)
            }
            if (!File(txtConfigPath.text).isFile) {
                return ValidationInfo("Selecciona el archivo .conf del test.", txtConfigPath)
            }
        }
        if (txtTestOutputPath.text.isBlank()) {
            return ValidationInfo("Selecciona el directorio de salida de los tests.", txtTestOutputPath)
        }
        return null
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addComponent(rbTestGenerate)
            .addComponent(rbTestGetData)
            .addComponent(rbTestParametry)
            .addSeparator()
            .addLabeledComponent("Archivo Scala a probar:", txtSourcePath)
            .addLabeledComponent("Carpeta con los parquet de muestra:", txtParquetPath)
            .addLabeledComponent("Archivo .conf del test:", txtConfigPath)
            .addComponent(chkUseSelection)
            .addLabeledComponent("Método u objeto específico (opcional):", txtMethod)
            .addSeparator()
            .addLabeledComponent("Directorio de salida del test (.scala):", txtTestOutputPath)
            .panel
        panel.preferredSize = Dimension(660, panel.preferredSize.height)
        return panel
    }
}