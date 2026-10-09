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
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

class GenerateDataDialog(basePath: String, initialSchemaPath: String) : DialogWrapper(true) {

    // El esquema es obligatorio para poder generar data de prueba
    val txtSchemaPath = TextFieldWithBrowseButton()

    val rbGenerateNew = JRadioButton("Generar data nueva (mock)", true)
    val rbModifyExisting = JRadioButton("Modificar parquet existente")
    val txtInputPath = TextFieldWithBrowseButton()

    val txtRecords = JTextField("100").apply {
        (document as AbstractDocument).documentFilter = object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                if (string?.all { it.isDigit() } == true) super.insertString(fb, offset, string, attr)
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                if (text?.all { it.isDigit() } == true) super.replace(fb, offset, length, text, attrs)
            }
        }
    }
    val comboPartitions = JComboBox(arrayOf("Sin Particiones", "1", "3", "5"))
    val checkEdgeCases = JCheckBox("Generar casos de borde (nulos, vacíos, valores extremos)")
    val txtOutputPath = TextFieldWithBrowseButton()

    init {
        title = "Generar Data de Prueba (Parquet)"

        ButtonGroup().apply {
            add(rbGenerateNew)
            add(rbModifyExisting)
        }

        val fileDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        val folderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        txtSchemaPath.addBrowseFolderListener("Seleccionar archivo de esquema", null, null, fileDescriptor)
        txtInputPath.addBrowseFolderListener("Seleccionar parquet de entrada", null, null, folderDescriptor)
        txtOutputPath.addBrowseFolderListener("Seleccionar directorio de salida", null, null, folderDescriptor)

        txtSchemaPath.text = initialSchemaPath
        txtOutputPath.text = "$basePath/src/test/resources/"

        fun updateState() {
            txtInputPath.isEnabled = rbModifyExisting.isSelected
            txtRecords.isEnabled = rbGenerateNew.isSelected
            checkEdgeCases.isEnabled = rbGenerateNew.isSelected
        }
        rbGenerateNew.addActionListener { updateState() }
        rbModifyExisting.addActionListener { updateState() }
        updateState()

        init()
    }

    override fun doValidate(): ValidationInfo? {
        if (!File(txtSchemaPath.text).isFile) {
            return ValidationInfo("Selecciona un archivo de esquema válido (obligatorio).", txtSchemaPath)
        }
        if (rbModifyExisting.isSelected && txtInputPath.text.isBlank()) {
            return ValidationInfo("Selecciona el parquet de entrada a modificar.", txtInputPath)
        }
        if (rbGenerateNew.isSelected && (txtRecords.text.toIntOrNull() ?: 0) <= 0) {
            return ValidationInfo("La cantidad de registros debe ser mayor que 0.", txtRecords)
        }
        if (txtOutputPath.text.isBlank()) {
            return ValidationInfo("Selecciona el directorio de salida.", txtOutputPath)
        }
        return null
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Archivo de esquema (obligatorio):", txtSchemaPath)
            .addSeparator()
            .addComponent(rbGenerateNew)
            .addComponent(rbModifyExisting)
            .addLabeledComponent("Parquet de entrada (solo al modificar):", txtInputPath)
            .addSeparator()
            .addLabeledComponent("Cantidad de registros:", txtRecords)
            .addLabeledComponent("Particiones:", comboPartitions)
            .addComponent(checkEdgeCases)
            .addSeparator()
            .addLabeledComponent("Directorio de salida del parquet:", txtOutputPath)
            .panel
        panel.preferredSize = Dimension(620, panel.preferredSize.height)
        return panel
    }
}