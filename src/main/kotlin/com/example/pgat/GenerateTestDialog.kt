package com.example.pgat

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.io.File
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSeparator
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
    // Solo Generate: carpeta de unit test (parquet de muestra)
    val txtParquetPath = TextFieldWithBrowseButton()
    // GetData y Generate: carpeta de inputs (parquet de entrada)
    val txtInputPath = TextFieldWithBrowseButton()
    // GetData y Generate
    val txtConfigPath = TextFieldWithBrowseButton()
    val chkUseSelection = JCheckBox("Usar solo el texto seleccionado en el editor", hasSelection)
    val txtMethod = JTextField()
    val txtTestOutputPath = TextFieldWithBrowseButton()

    private val sourceRow = labeled("Archivo Scala a probar:", txtSourcePath)
    private val unitTestRow = labeled("Carpeta de unit test (parquet de muestra):", txtParquetPath)
    private val inputsRow = labeled("Carpeta de inputs (parquet de entrada):", txtInputPath)
    private val configRow = labeled("Archivo .conf del test:", txtConfigPath)
    private val methodRow = labeled("Método u objeto específico (opcional):", txtMethod)
    private val outputRow = labeled("Directorio de salida del test (.scala):", txtTestOutputPath)

    private var ready = false

    private fun fileChooser() = FileChooserDescriptor(true, false, false, false, false, false)
    private fun folderChooser() = FileChooserDescriptor(false, true, false, false, false, false)

    /** Fila con etiqueta a la izquierda y campo a la derecha. Las etiquetas comparten ancho. */
    private fun labeled(text: String, field: JComponent): JPanel =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            val label = JBLabel(text)
            label.preferredSize = Dimension(JBUI.scale(290), label.preferredSize.height)
            add(label, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }

    init {
        title = "Generar Test Unitario (ScalaTest)"

        ButtonGroup().apply {
            add(rbTestGenerate)
            add(rbTestGetData)
            add(rbTestParametry)
        }

        txtSourcePath.addBrowseFolderListener(
            "Seleccionar archivo Scala a probar", null, null, fileChooser()
        )
        txtParquetPath.addBrowseFolderListener(
            "Seleccionar carpeta de unit test con los parquet de muestra", null, null, folderChooser()
        )
        txtInputPath.addBrowseFolderListener(
            "Seleccionar carpeta con los inputs (parquet de entrada)", null, null, folderChooser()
        )
        txtConfigPath.addBrowseFolderListener(
            "Seleccionar archivo .conf del test", null, null, fileChooser()
        )
        txtTestOutputPath.addBrowseFolderListener(
            "Seleccionar directorio de salida de tests", null, null, folderChooser()
        )

        txtSourcePath.text = initialSourcePath

        // Valores por defecto. Se revisan con ruta corta (LongPaths); el texto que se muestra es el original.
        val uniTest = File("$basePath/src/test/resources/UniTest")
        txtParquetPath.text = if (LongPaths.resolve(uniTest, true).isDirectory) uniTest.path
        else "$basePath/src/test/resources"

        val resources = File("$basePath/src/test/resources")
        txtInputPath.text = listOf("inputs", "input")
            .map { File(resources, it) }
            .firstOrNull { LongPaths.resolve(it, true).isDirectory }
            ?.path
            ?: ""

        val cfgDir = File("$basePath/src/test/resources/config")
        txtConfigPath.text = LongPaths.resolve(cfgDir, true)
            .listFiles { f -> f.isFile && f.extension == "conf" }
            ?.minByOrNull { it.name }
            ?.let { File(cfgDir, it.name).path }
            ?: ""

        txtTestOutputPath.text = "$basePath/src/test/scala/com/bbva/co/csan/data/"
        chkUseSelection.isEnabled = hasSelection

        rbTestGenerate.addActionListener { updateState() }
        rbTestGetData.addActionListener { updateState() }
        rbTestParametry.addActionListener { updateState() }
        updateState()

        init()
        ready = true
    }

    /** Muestra solo las filas que usa cada modo. */
    private fun updateState() {
        val isGenerate = rbTestGenerate.isSelected
        val needsData = !rbTestParametry.isSelected

        unitTestRow.isVisible = isGenerate   // Generate: unit test + inputs
        inputsRow.isVisible = needsData      // GetData y Generate
        configRow.isVisible = needsData      // GetData y Generate

        if (ready) pack()
    }

    private fun isExistingFile(path: String) =
        path.isNotBlank() && LongPaths.resolve(File(path), true).isFile

    private fun isExistingDir(path: String) =
        path.isNotBlank() && LongPaths.resolve(File(path), true).isDirectory

    override fun doValidate(): ValidationInfo? {
        if (!chkUseSelection.isSelected && !isExistingFile(txtSourcePath.text)) {
            return ValidationInfo("Selecciona el archivo Scala que quieres probar.", txtSourcePath)
        }
        if (rbTestGenerate.isSelected && !isExistingDir(txtParquetPath.text)) {
            return ValidationInfo("Selecciona la carpeta de unit test con los parquet de muestra.", txtParquetPath)
        }
        if (!rbTestParametry.isSelected) {
            if (!isExistingDir(txtInputPath.text)) {
                return ValidationInfo("Selecciona la carpeta con los inputs (parquet de entrada).", txtInputPath)
            }
            if (!isExistingFile(txtConfigPath.text)) {
                return ValidationInfo("Selecciona el archivo .conf del test.", txtConfigPath)
            }
        }
        if (txtTestOutputPath.text.isBlank()) {
            return ValidationInfo("Selecciona el directorio de salida de los tests.", txtTestOutputPath)
        }
        return null
    }

    override fun createCenterPanel(): JComponent {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf<JComponent>(
                rbTestGenerate,
                rbTestGetData,
                rbTestParametry,
                JSeparator(),
                sourceRow,
                unitTestRow,
                inputsRow,
                configRow,
                chkUseSelection,
                methodRow,
                JSeparator(),
                outputRow
            ).forEach { c ->
                c.alignmentX = Component.LEFT_ALIGNMENT
                add(c)
            }
        }

        // Ancho mínimo fijo; el alto cambia solo cuando se ocultan filas.
        return object : JPanel(BorderLayout()) {
            override fun getPreferredSize(): Dimension {
                val d = super.getPreferredSize()
                return Dimension(maxOf(d.width, JBUI.scale(780)), d.height)
            }
        }.apply {
            add(column, BorderLayout.NORTH)
        }
    }
}