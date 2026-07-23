package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.logic.AgentSettingsService
import java.awt.BorderLayout
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * The Relay Settings page (task 3.3, design D10) — the plugin's first `Configurable`, registered via
 * `<applicationConfigurable>` in `plugin.xml`:
 *
 * ```xml
 * <applicationConfigurable id="io.github.zerlok.agentsessionrelay.settings"
 *     displayName="Agent Session Relay"
 *     instance="io.github.zerlok.agentsessionrelay.ui.RelayConfigurable"/>
 * ```
 *
 * It is a pure view (ARCHITECTURE §3.1): it holds no storage handle and reads/writes exclusively through
 * [AgentSettingsService]. It offers CRUD over the named start-script configs (design D10: the only source of
 * anything the plugin executes) and the two per-event sound toggles (agent-notifications spec; both ON by
 * default). Nothing here writes any agent config file (design D12).
 */
class RelayConfigurable : Configurable {

    private val tableModel = ConfigTableModel()
    private var turnCompletedSound = JBCheckBox("Play a sound on turn completion")
    private var needsInputSound = JBCheckBox("Play a sound on needs-input")
    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Agent Session Relay"

    override fun createComponent(): JComponent {
        val table = JBTable(tableModel).apply {
            // Environment column edits via a combo of the four kinds; the model parses the name back.
            columnModel.getColumn(COL_ENVIRONMENT).cellEditor =
                DefaultCellEditor(JComboBox(AgentEnvironment.entries.map { it.name }.toTypedArray()))
        }
        val configsPanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { tableModel.addRow() }
            .setRemoveAction { table.selectedRow.takeIf { it >= 0 }?.let(tableModel::removeRow) }
            .createPanel()

        val soundsPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(8)
            add(JBLabel("Notification sounds"))
            add(turnCompletedSound)
            add(needsInputSound)
        }

        return JPanel(BorderLayout()).also { root ->
            root.add(JBLabel("Start-script configs — launched into a terminal in the Agent Sessions tool window"), BorderLayout.NORTH)
            root.add(configsPanel, BorderLayout.CENTER)
            root.add(soundsPanel, BorderLayout.SOUTH)
            panel = root
            reset()
        }
    }

    override fun isModified(): Boolean {
        val settings = AgentSettingsService.getInstance()
        return tableModel.configs() != settings.configs() ||
            turnCompletedSound.isSelected != settings.isTurnCompletedSoundEnabled() ||
            needsInputSound.isSelected != settings.isNeedsInputSoundEnabled()
    }

    override fun apply() {
        val settings = AgentSettingsService.getInstance()
        settings.setConfigs(tableModel.configs())
        settings.setTurnCompletedSoundEnabled(turnCompletedSound.isSelected)
        settings.setNeedsInputSoundEnabled(needsInputSound.isSelected)
    }

    override fun reset() {
        val settings = AgentSettingsService.getInstance()
        tableModel.setConfigs(settings.configs())
        turnCompletedSound.isSelected = settings.isTurnCompletedSoundEnabled()
        needsInputSound.isSelected = settings.isNeedsInputSoundEnabled()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private companion object {
        const val COL_NAME = 0
        const val COL_COMMAND = 1
        const val COL_ENVIRONMENT = 2
        const val COL_TURN_STARTED = 3
        const val COL_NEEDS_INPUT = 4
    }

    /**
     * A mutable editing model over a working copy of the configs; [EnvironmentConfig] is immutable, so a cell
     * edit replaces its row via `copy`. `apply()` writes [configs] to the service; `reset()` reloads them.
     */
    private class ConfigTableModel : AbstractTableModel() {
        private val rows = mutableListOf<EnvironmentConfig>()

        fun configs(): List<EnvironmentConfig> = rows.toList()

        fun setConfigs(configs: List<EnvironmentConfig>) {
            rows.clear()
            rows.addAll(configs)
            fireTableDataChanged()
        }

        fun addRow() {
            rows.add(EnvironmentConfig(name = "new-config", command = ""))
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeRow(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = when (column) {
            COL_NAME -> "Name"
            COL_COMMAND -> "Command"
            COL_ENVIRONMENT -> "Environment"
            COL_TURN_STARTED -> "turn_started"
            COL_NEEDS_INPUT -> "needs_input"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_TURN_STARTED, COL_NEEDS_INPUT -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val config = rows[rowIndex]
            return when (columnIndex) {
                COL_NAME -> config.name
                COL_COMMAND -> config.command
                COL_ENVIRONMENT -> config.environment.name
                COL_TURN_STARTED -> config.capabilities.turnStarted
                COL_NEEDS_INPUT -> config.capabilities.needsInput
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val config = rows[rowIndex]
            rows[rowIndex] = when (columnIndex) {
                COL_NAME -> config.copy(name = value?.toString().orEmpty())
                COL_COMMAND -> config.copy(command = value?.toString().orEmpty())
                COL_ENVIRONMENT -> config.copy(
                    environment = runCatching { AgentEnvironment.valueOf(value?.toString().orEmpty()) }
                        .getOrDefault(AgentEnvironment.CUSTOM),
                )
                COL_TURN_STARTED -> config.copy(
                    capabilities = config.capabilities.copy(turnStarted = value == true),
                )
                COL_NEEDS_INPUT -> config.copy(
                    capabilities = config.capabilities.copy(needsInput = value == true),
                )
                else -> config
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }
}
