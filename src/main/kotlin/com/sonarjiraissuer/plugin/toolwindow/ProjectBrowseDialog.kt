package com.sonarjiraissuer.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sonarjiraissuer.plugin.api.SonarProject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal dialog for browsing and selecting a SonarQube project.
 *
 * Presents a searchable list of projects accessible with the current token.
 * The selected project key is available via [selectedProject] after [showAndGet] returns true.
 *
 * Inspired by the project-picker pattern used in the SonarLint plugin.
 */
class ProjectBrowseDialog(
    project: Project,
    private val allProjects: List<SonarProject>
) : DialogWrapper(project) {

    private val searchField = JBTextField().apply {
        emptyText.text = "Type to filter projects…"
    }

    private val listModel   = DefaultListModel<SonarProject>()
    private val projectList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    /** The project chosen by the user, or null if the dialog was cancelled. */
    var selectedProject: SonarProject? = null

    init {
        title = "Browse SonarQube Projects"
        setOKButtonText("Select")
        init()
        populateList("")

        // Enable/disable OK when selection changes
        projectList.selectionModel.addListSelectionListener {
            okAction.isEnabled = !projectList.isSelectionEmpty
        }
        okAction.isEnabled = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border        = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(420))
        }

        // ── Search bar ──────────────────────────────────────────────────────────
        val searchPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(JBLabel("Filter:"), BorderLayout.WEST)
            add(searchField,        BorderLayout.CENTER)
        }

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = populateList(searchField.text)
            override fun removeUpdate(e: DocumentEvent)  = populateList(searchField.text)
            override fun changedUpdate(e: DocumentEvent) = populateList(searchField.text)
        })

        panel.add(searchPanel, BorderLayout.NORTH)

        // ── Project list ────────────────────────────────────────────────────────
        projectList.setCellRenderer { list, value, _, selected, _ ->
            JBLabel(
                if (value.name == value.key) value.key
                else "${value.name}  (${value.key})"
            ).apply {
                isOpaque   = true
                background = if (selected) list.selectionBackground else list.background
                foreground = if (selected) list.selectionForeground else list.foreground
                border     = JBUI.Borders.empty(3, 8)
            }
        }

        // Double-click acts as OK
        projectList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !projectList.isSelectionEmpty) doOKAction()
            }
        })

        panel.add(JBScrollPane(projectList), BorderLayout.CENTER)

        // ── Footer count ────────────────────────────────────────────────────────
        val countLabel = JBLabel("${allProjects.size} project(s) accessible with this token").apply {
            foreground = JBColor.GRAY
            font       = JBUI.Fonts.smallFont()
        }
        panel.add(countLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun getPreferredFocusedComponent() = searchField

    private fun populateList(query: String) {
        listModel.clear()
        val q = query.trim().lowercase()
        allProjects
            .filter { p ->
                q.isEmpty()
                    || p.key.lowercase().contains(q)
                    || p.name.lowercase().contains(q)
            }
            .forEach { listModel.addElement(it) }
    }

    override fun doOKAction() {
        selectedProject = projectList.selectedValue
        super.doOKAction()
    }
}
