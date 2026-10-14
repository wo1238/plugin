package com.example.pgat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.util.regex.Pattern
import javax.swing.ButtonGroup
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

class GenerateDataDialog(private val basePath: String, initialSchemaPath: String) : DialogWrapper(true) {

    companion object {
        private const val COL_NAME = 0
        private const val COL_TYPE = 1
        private const val COL_PART = 2
        private const val COL_MODE = 3
        private const val COL_TEXT = 4

        private const val M_AUTO = "Automático"
        private const val M_AI = "IA"
        private const val M_VALUES = "Valores"
    }

    /** Un valor actual de la columna, tal como se muestra en el combo. */
    private class ValueItem(val value: String?, val count: Long) {
        override fun toString(): String {
            val shown = when {
                value == null -> "(nulo)"
                value.isEmpty() -> "(vacío)"
                else -> value
            }
            return "$shown    ·    $count fila${if (count == 1L) "" else "s"}"
        }
    }

    private val tabs = JBTabbedPane()

    // =====================================================================
    // Pestaña 1: generar data nueva
    // =====================================================================
    val txtSchemaFolder = TextFieldWithBrowseButton()
    val comboSchema = JComboBox<String>()
    private val lblSchemaInfo = JBLabel(" ").apply { foreground = JBColor.GRAY }

    val txtRecords = JTextField("100", 8).apply {
        (document as AbstractDocument).documentFilter = object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                if (string?.all { it.isDigit() } == true) super.insertString(fb, offset, string, attr)
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                if (text?.all { it.isDigit() } == true) super.replace(fb, offset, length, text, attrs)
            }
        }
    }
    val rbPartFolders = JRadioButton("En carpetas  (campo=valor/…)", true)
    val rbPartColumn = JRadioButton("Como columna normal")
    val checkEdgeCases = JCheckBox("Incluir casos de borde (nulos, vacíos, extremos)")
    val txtOutputPath = TextFieldWithBrowseButton()

    private val search = JBTextField().apply { emptyText.text = "Buscar campo…" }

    private val tableModel: DefaultTableModel = object : DefaultTableModel(
        arrayOf("Campo", "Tipo", "Partición", "Origen", "Detalle"), 0
    ) {
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == COL_PART) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = when (column) {
            COL_PART, COL_MODE -> true
            COL_TEXT -> (getValueAt(row, COL_MODE) as String) != M_AUTO || (getValueAt(row, COL_PART) as Boolean)
            else -> false
        }
    }
    private val table = JBTable(tableModel)
    private val sorter = TableRowSorter<DefaultTableModel>(tableModel)
    private var adjusting = false

    private var schemaFiles: List<File> = emptyList()

    /** Esquema cargado actualmente (null si no se pudo leer). */
    var schema: SchemaDef? = null
        private set
    private var schemaError: String? = null

    // =====================================================================
    // Pestaña 2: modificar parquet existente
    // =====================================================================
    val txtInputPath = TextFieldWithBrowseButton()
    private val comboColumn = JComboBox<String>()
    private val comboValue = JComboBox<ValueItem>()
    val txtModNew = JTextField()
    private val lblModInfo = JBLabel(" ").apply { foreground = JBColor.GRAY }
    private val lblColInfo = JBLabel(" ").apply { foreground = JBColor.GRAY }

    private var parquetInfo: ParquetInfo? = null
    private var modColumns: List<String> = emptyList()
    private var populating = false
    private var infoSeq = 0
    private var valuesSeq = 0

    val isModifyMode: Boolean get() = tabs.selectedIndex == 1

    val partitionStorage: PartitionStorage
        get() = if (rbPartFolders.isSelected) PartitionStorage.FOLDERS else PartitionStorage.COLUMN

    /** Columna elegida para modificar. */
    val selectedModColumn: String
        get() = modColumns.getOrNull(comboColumn.selectedIndex).orEmpty()

    /** Valor actual elegido (null = filas que hoy tienen nulo). Solo válido si [hasSelectedValue]. */
    val selectedOldValue: String?
        get() = (comboValue.selectedItem as? ValueItem)?.value

    val hasSelectedValue: Boolean
        get() = comboValue.selectedItem is ValueItem

    init {
        title = "Generar Data de Prueba (Parquet)"

        ButtonGroup().apply {
            add(rbPartFolders)
            add(rbPartColumn)
        }

        val folderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        txtSchemaFolder.addBrowseFolderListener("Seleccionar carpeta con los .schema", null, null, folderDescriptor)
        txtInputPath.addBrowseFolderListener("Seleccionar parquet a modificar", null, null, folderDescriptor)
        txtOutputPath.addBrowseFolderListener("Seleccionar directorio de salida", null, null, folderDescriptor)
        txtOutputPath.text = "$basePath/src/test/resources/"

        setupTable()

        // El esquema inicial puede ser un archivo .schema o una carpeta
        val initial = File(initialSchemaPath)
        var preselect: File? = null
        when {
            initialSchemaPath.isNotBlank() && initial.isFile && initial.name.endsWith(".schema", true) -> {
                txtSchemaFolder.text = initial.parentFile?.absolutePath.orEmpty()
                preselect = initial
            }
            initialSchemaPath.isNotBlank() && initial.isDirectory -> txtSchemaFolder.text = initial.absolutePath
        }

        txtSchemaFolder.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                reloadSchemaList(null)
            }
        })
        comboSchema.addActionListener { if (!populating) loadSelectedSchema() }

        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val t = search.text.trim()
                sorter.rowFilter =
                    if (t.isEmpty()) null else RowFilter.regexFilter("(?i)" + Pattern.quote(t), COL_NAME)
            }
        })

        // Modificar: al elegir carpeta se leen las columnas; al elegir columna, sus valores actuales
        txtInputPath.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                loadParquetInfo()
            }
        })
        comboColumn.addActionListener { if (!populating) loadColumnValues() }

        reloadSchemaList(preselect)

        init()
    }

    // ------------------------------------------------------------------
    // Tabla de campos
    // ------------------------------------------------------------------

    private fun hintFor(mode: String, partition: Boolean): String = when {
        partition -> "Valores de la partición, ej: 2026,2025"
        mode == M_AI -> "Indicación para la IA (opcional), ej: nombres de clientes"
        mode == M_VALUES -> "Valores separados por coma, o un valor fijo"
        else -> "Se genera según el tipo"
    }

    private fun setupTable() {
        table.setStriped(true)
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(28)
        table.tableHeader.reorderingAllowed = false
        table.rowSorter = sorter

        val cm = table.columnModel
        cm.getColumn(COL_NAME).preferredWidth = 210
        cm.getColumn(COL_TYPE).preferredWidth = 120
        cm.getColumn(COL_PART).preferredWidth = 70
        cm.getColumn(COL_PART).maxWidth = 90
        cm.getColumn(COL_MODE).preferredWidth = 110
        cm.getColumn(COL_MODE).maxWidth = 130
        cm.getColumn(COL_TEXT).preferredWidth = 330

        // Origen: combo siempre visible para que se note que se puede cambiar
        cm.getColumn(COL_MODE).cellEditor = DefaultCellEditor(JComboBox(arrayOf(M_AUTO, M_AI, M_VALUES)))
        cm.getColumn(COL_MODE).cellRenderer = object : TableCellRenderer {
            private val box = JComboBox(arrayOf(M_AUTO, M_AI, M_VALUES))
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                box.selectedItem = value
                return box
            }
        }

        // Tipo en gris
        cm.getColumn(COL_TYPE).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                foreground = if (isSelected) t.selectionForeground else JBColor.GRAY
                return c
            }
        }

        // Detalle: texto de ayuda en gris cuando está vacío
        cm.getColumn(COL_TEXT).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val mr = t.convertRowIndexToModel(row)
                val mode = tableModel.getValueAt(mr, COL_MODE) as String
                val part = tableModel.getValueAt(mr, COL_PART) as Boolean
                val text = (value as? String).orEmpty()
                val c = super.getTableCellRendererComponent(
                    t, if (text.isEmpty()) hintFor(mode, part) else text, isSelected, hasFocus, row, column
                )
                foreground = when {
                    isSelected -> t.selectionForeground
                    text.isEmpty() -> JBColor.GRAY
                    else -> t.foreground
                }
                return c
            }
        }

        // Al marcar un campo como partición se le pone modo Valores y valores por defecto
        tableModel.addTableModelListener { e ->
            if (adjusting || e.type != TableModelEvent.UPDATE || e.column != COL_PART) return@addTableModelListener
            val row = e.firstRow
            if (row < 0 || row >= tableModel.rowCount) return@addTableModelListener
            val isPart = tableModel.getValueAt(row, COL_PART) as Boolean
            if (isPart) {
                val f = schema?.fields?.firstOrNull { it.name == tableModel.getValueAt(row, COL_NAME) }
                adjusting = true
                try {
                    tableModel.setValueAt(M_VALUES, row, COL_MODE)
                    if ((tableModel.getValueAt(row, COL_TEXT) as? String).isNullOrBlank() && f != null) {
                        tableModel.setValueAt(DataGenerator.defaultPartitionValues(f), row, COL_TEXT)
                    }
                } finally {
                    adjusting = false
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Carga del esquema
    // ------------------------------------------------------------------

    private fun reloadSchemaList(preselect: File?) {
        val folder = File(txtSchemaFolder.text.trim())
        schemaFiles = SchemaReader.listSchemas(folder)

        populating = true
        try {
            comboSchema.removeAllItems()
            schemaFiles.forEach { f ->
                val rel = runCatching { f.relativeTo(folder).path }.getOrDefault(f.name).replace("\\", "/")
                comboSchema.addItem(rel)
            }
        } finally {
            populating = false
        }

        if (schemaFiles.isEmpty()) {
            schema = null
            schemaError = if (txtSchemaFolder.text.isBlank()) null else "No se encontraron archivos .schema en esa carpeta."
            tableModel.rowCount = 0
            lblSchemaInfo.text = schemaError ?: " "
            return
        }
        val idx = preselect?.let { p -> schemaFiles.indexOfFirst { it.absolutePath == p.absolutePath } } ?: -1
        populating = true
        try {
            comboSchema.selectedIndex = if (idx >= 0) idx else 0
        } finally {
            populating = false
        }
        loadSelectedSchema()
    }

    private fun loadSelectedSchema() {
        val idx = comboSchema.selectedIndex
        tableModel.rowCount = 0
        if (idx < 0 || idx >= schemaFiles.size) {
            schema = null
            return
        }
        try {
            val s = SchemaReader.read(schemaFiles[idx])
            schema = s
            schemaError = null
            adjusting = true
            try {
                s.fields.forEach { f ->
                    val isPart = f.name in s.partitions
                    val type = buildString {
                        append(f.rawType)
                        if (f.kind == FieldKind.STRING && f.length != null) append("(${f.length})")
                    }
                    tableModel.addRow(
                        arrayOf<Any>(
                            f.name,
                            type,
                            isPart,
                            if (isPart) M_VALUES else M_AUTO,
                            if (isPart) DataGenerator.defaultPartitionValues(f) else ""
                        )
                    )
                }
            } finally {
                adjusting = false
            }
            lblSchemaInfo.text = "${s.name}  ·  ${s.fields.size} campos  ·  " +
                    if (s.partitions.isEmpty()) "sin particiones" else "particiones: ${s.partitions.joinToString(", ")}"
        } catch (ex: Exception) {
            schema = null
            schemaError = "No se pudo leer el .schema: ${ex.message}"
            lblSchemaInfo.text = schemaError ?: " "
        }
    }

    // ------------------------------------------------------------------
    // Modificar: columnas y valores actuales
    // ------------------------------------------------------------------

    private fun loadParquetInfo() {
        val seq = ++infoSeq
        valuesSeq++
        val dir = File(txtInputPath.text.trim())

        populating = true
        try {
            comboColumn.removeAllItems()
            comboValue.removeAllItems()
        } finally {
            populating = false
        }
        parquetInfo = null
        modColumns = emptyList()
        lblColInfo.text = " "

        if (!dir.isDirectory) {
            lblModInfo.text = if (txtInputPath.text.isBlank()) " " else "La carpeta no existe."
            return
        }
        lblModInfo.text = "Leyendo parquet…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DataGenerator.inspect(dir) }
            ApplicationManager.getApplication().invokeLater({
                if (seq != infoSeq) return@invokeLater
                result.onSuccess { info ->
                    parquetInfo = info
                    modColumns = info.columns.map { it.name }
                    populating = true
                    try {
                        info.columns.forEach { c ->
                            comboColumn.addItem(
                                "${c.name}   (${c.type})" + if (c.isPartition) "   · partición" else ""
                            )
                        }
                        comboColumn.selectedIndex = -1
                    } finally {
                        populating = false
                    }
                    lblModInfo.text = "${info.rows} filas  ·  ${info.columns.size} columnas  ·  " +
                            if (info.partitions.isEmpty()) "sin particiones"
                            else "particiones: ${info.partitions.joinToString(", ")}"
                }.onFailure { ex ->
                    lblModInfo.text = "No se pudo leer: ${ex.message.orEmpty().take(160)}"
                }
            }, ModalityState.any())
        }
    }

    private fun loadColumnValues() {
        val seq = ++valuesSeq
        val col = selectedModColumn
        val dir = File(txtInputPath.text.trim())

        populating = true
        try {
            comboValue.removeAllItems()
        } finally {
            populating = false
        }
        if (col.isEmpty()) {
            lblColInfo.text = " "
            return
        }
        lblColInfo.text = "Leyendo valores…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DataGenerator.distinctValues(dir, col) }
            ApplicationManager.getApplication().invokeLater({
                if (seq != valuesSeq) return@invokeLater
                result.onSuccess { cv ->
                    populating = true
                    try {
                        cv.values.forEach { comboValue.addItem(ValueItem(it.value, it.count)) }
                        comboValue.selectedIndex = if (cv.values.isNotEmpty()) 0 else -1
                    } finally {
                        populating = false
                    }
                    lblColInfo.text = "${cv.distinct} valor${if (cv.distinct == 1L) "" else "es"} distinto${if (cv.distinct == 1L) "" else "s"}" +
                            if (cv.truncated) "  ·  se muestran los ${cv.values.size} más frecuentes" else ""
                }.onFailure { ex ->
                    lblColInfo.text = "No se pudieron leer los valores: ${ex.message.orEmpty().take(160)}"
                }
            }, ModalityState.any())
        }
    }

    // ------------------------------------------------------------------
    // Lectura de lo que eligió el desarrollador
    // ------------------------------------------------------------------

    /** Un FieldSpec por fila de la tabla (en el orden del .schema). */
    fun collectSpecs(): List<FieldSpec> {
        val s = schema ?: return emptyList()
        val byName = s.fields.associateBy { it.name }
        val specs = ArrayList<FieldSpec>()
        for (row in 0 until tableModel.rowCount) {
            val name = tableModel.getValueAt(row, COL_NAME) as String
            val f = byName[name] ?: continue
            val mode = when (tableModel.getValueAt(row, COL_MODE) as String) {
                M_AI -> FieldMode.AI
                M_VALUES -> FieldMode.VALUES
                else -> FieldMode.AUTO
            }
            val isPart = tableModel.getValueAt(row, COL_PART) as? Boolean ?: false
            val text = (tableModel.getValueAt(row, COL_TEXT) as? String).orEmpty()
            specs.add(FieldSpec(f, mode, text, isPart))
        }
        return specs
    }

    override fun doOKAction() {
        // Confirma la celda que se esté editando para no perder el último valor escrito
        table.cellEditor?.stopCellEditing()
        super.doOKAction()
    }

    override fun doValidate(): ValidationInfo? {
        table.cellEditor?.stopCellEditing()

        if (!isModifyMode) {
            if (!File(txtSchemaFolder.text.trim()).isDirectory) {
                return ValidationInfo("Selecciona la carpeta que contiene los .schema.", txtSchemaFolder)
            }
            if (schema == null) {
                return ValidationInfo(schemaError ?: "Selecciona un archivo .schema válido.", comboSchema)
            }
            if ((txtRecords.text.toIntOrNull() ?: 0) <= 0) {
                return ValidationInfo("La cantidad de registros debe ser mayor que 0.", txtRecords)
            }
            if (txtOutputPath.text.isBlank()) {
                return ValidationInfo("Selecciona el directorio de salida.", txtOutputPath)
            }
        } else {
            if (!File(txtInputPath.text.trim()).isDirectory) {
                return ValidationInfo("Selecciona la carpeta del parquet a modificar.", txtInputPath)
            }
            if (parquetInfo == null) {
                return ValidationInfo("Espera a que se lea el parquet o revisa que la carpeta tenga archivos .parquet.", txtInputPath)
            }
            if (selectedModColumn.isEmpty()) {
                return ValidationInfo("Elige la columna a modificar.", comboColumn)
            }
            if (!hasSelectedValue) {
                return ValidationInfo("Elige el valor actual que quieres cambiar.", comboValue)
            }
            if (txtModNew.text.isEmpty()) {
                return ValidationInfo("Escribe el valor nuevo (NULL para dejarlo nulo).", txtModNew)
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun generateTab(): JComponent {
        val top = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Esquema"))
            .addLabeledComponent("Carpeta de .schema:", txtSchemaFolder)
            .addLabeledComponent("Archivo:", comboSchema)
            .addComponent(lblSchemaInfo)
            .addComponent(TitledSeparator("Campos"))
            .panel

        val btnAuto = JButton("Todo automático").apply {
            addActionListener {
                for (r in 0 until tableModel.rowCount) {
                    if (tableModel.getValueAt(r, COL_PART) as Boolean) continue
                    tableModel.setValueAt(M_AUTO, r, COL_MODE)
                    tableModel.setValueAt("", r, COL_TEXT)
                }
            }
        }
        val btnAi = JButton("Texto con IA").apply {
            toolTipText = "Pone en modo IA los campos de texto que no son llave ni partición"
            addActionListener {
                val byName = schema?.fields?.associateBy { it.name } ?: return@addActionListener
                for (r in 0 until tableModel.rowCount) {
                    if (tableModel.getValueAt(r, COL_PART) as Boolean) continue
                    val f = byName[tableModel.getValueAt(r, COL_NAME) as String] ?: continue
                    if (f.kind == FieldKind.STRING && !f.pk) tableModel.setValueAt(M_AI, r, COL_MODE)
                }
            }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            add(btnAuto)
            add(btnAi)
        }
        val toolbar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(search, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        val scroll = JBScrollPane(table).apply { preferredSize = Dimension(840, 250) }
        val center = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }

        val partRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            add(rbPartFolders)
            add(rbPartColumn)
        }
        val bottom = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Salida"))
            .addLabeledComponent("Registros:", txtRecords)
            .addLabeledComponent("Partición:", partRow)
            .addComponent(checkEdgeCases)
            .addLabeledComponent("Guardar en:", txtOutputPath)
            .panel

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun modifyTab(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Parquet"))
            .addLabeledComponent("Carpeta del parquet:", txtInputPath)
            .addComponent(lblModInfo)
            .addComponent(TitledSeparator("Cambio"))
            .addLabeledComponent("Columna:", comboColumn)
            .addLabeledComponent("Valor actual:", comboValue)
            .addComponent(lblColInfo)
            .addLabeledComponent("Valor nuevo:", txtModNew)
            .addComponent(
                JBLabel("Escribe NULL para dejar el campo nulo. Se mantienen las particiones que ya tiene el parquet.").apply {
                    foreground = JBColor.GRAY
                }
            )
            .panel
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(form, BorderLayout.NORTH)
        }
    }

    override fun createCenterPanel(): JComponent {
        tabs.addTab("Generar data nueva", generateTab())
        tabs.addTab("Modificar parquet", modifyTab())
        tabs.preferredSize = Dimension(880, 640)
        return tabs
    }
}