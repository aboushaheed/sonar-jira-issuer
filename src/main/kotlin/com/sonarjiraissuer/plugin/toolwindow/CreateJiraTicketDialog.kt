package com.sonarjiraissuer.plugin.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sonarjiraissuer.plugin.api.JiraApiException
import com.sonarjiraissuer.plugin.api.JiraClient
import com.sonarjiraissuer.plugin.api.JiraFieldMeta
import com.sonarjiraissuer.plugin.api.JiraIssueType
import com.sonarjiraissuer.plugin.api.JiraProject
import com.sonarjiraissuer.plugin.model.IssueGroup
import com.sonarjiraissuer.plugin.model.JiraFormatterConfig
import com.sonarjiraissuer.plugin.service.JiraTextFormatter
import com.sonarjiraissuer.plugin.service.StoryPointCalculator
import com.sonarjiraissuer.plugin.settings.PluginSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal dialog that lets the user configure how SonarQube issue groups will be
 * pushed to Jira as tickets.
 *
 * ## Flow
 * 1. Opens → loads Jira projects asynchronously
 * 2. User selects project → issue types loaded
 * 3. User selects issue type → field metadata loaded, form rebuilt
 * 4. User fills / adjusts fields (priority, labels, any required extra fields)
 * 5. Clicks "Create N Ticket(s)" → dialog closes, caller reads public properties
 *
 * ## After `showAndGet() == true`
 * Callers read [selectedProject], [selectedIssueType] and [collectedFields] to
 * drive the actual ticket-creation background task.
 */
class CreateJiraTicketDialog(
    private val ideProject: Project,
    private val issueGroups: List<IssueGroup>,
    private val settings: PluginSettings,
    private val formatterConfig: JiraFormatterConfig
) : DialogWrapper(ideProject) {

    private val log = Logger.getInstance(CreateJiraTicketDialog::class.java)

    private val jiraClient = JiraClient(
        settings.jiraServerUrl,
        settings.loadJiraToken(),
        settings.jiraEmail
    )

    // ── Public results (available after showAndGet() == true) ─────────────────

    var selectedProject: JiraProject?    = null; private set
    var selectedIssueType: JiraIssueType? = null; private set
    /** Fields to merge into every ticket (labels, priority, optional extras). */
    var collectedFields: Map<String, Any>  = emptyMap(); private set

    // ── Project search ─────────────────────────────────────────────────────────

    /** Debounce timer: fires an API search 400 ms after the user stops typing. */
    private var searchDebounce: Timer? = null

    private val projectSearchField = JBTextField().apply {
        emptyText.text = "Filter by project name or key…"
        preferredSize  = Dimension(JBUI.scale(320), preferredSize.height)
    }

    // ── Combo boxes ────────────────────────────────────────────────────────────

    private val projectCombo = ComboBox<JiraProject>().apply {
        preferredSize = Dimension(JBUI.scale(320), preferredSize.height)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, selected, focus).also {
                (it as? javax.swing.JLabel)?.text =
                    if (value is JiraProject) "${value.key}  —  ${value.name}"
                    else value?.toString() ?: ""
            }
        }
    }

    private val issueTypeCombo = ComboBox<JiraIssueType>().apply {
        isEnabled = false
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, selected, focus).also {
                (it as? javax.swing.JLabel)?.text = (value as? JiraIssueType)?.name ?: ""
            }
        }
    }

    // ── Known field inputs (stable refs) ──────────────────────────────────────

    private val labelsField   = JBTextField(settings.jiraLabels, 28)
    private val priorityCombo = ComboBox<String>()
    private val priorityField = JBTextField(settings.jiraPriority, 28)
    private var usePriorityCombo = false

    /** Extra required fields discovered from the Jira createmeta API. */
    private val extraInputs = mutableMapOf<String, JBTextField>()

    // ── Layout panels ──────────────────────────────────────────────────────────

    private val fieldsContainer = JPanel(GridBagLayout())
    private val infoArea = JTextArea().apply {
        isEditable = false
        background = null
        border     = null
        font       = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11f).toInt())
    }
    private val statusLabel = JBLabel("Connecting to ${settings.jiraServerUrl}…").apply { foreground = JBColor.GRAY }
    private val errorLabel  = JBLabel("").apply { foreground = JBColor.RED; isVisible = false }

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        title = "Create Jira Tickets"
        setOKButtonText("Create ${issueGroups.size} Ticket(s)")
        isOKActionEnabled = false
        init()              // DialogWrapper.init() — must be called last in init {}
        loadProjectsAsync()
    }

    // ── Center panel ──────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        root.add(sectionPanel("Jira Target") {
            // Search field stacked above the project combo in the same logical row
            val projectColumn = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(projectSearchField.also { it.alignmentX = 0f })
                add(Box.createVerticalStrut(JBUI.scale(3)))
                add(projectCombo.also { it.alignmentX = 0f })
            }
            addRow("Project:",    projectColumn)
            addRow("Issue Type:", issueTypeCombo)
        })
        root.add(Box.createVerticalStrut(JBUI.scale(6)))

        root.add(sectionPanel("Ticket Fields") {
            addComponent(fieldsContainer)
        })
        root.add(Box.createVerticalStrut(JBUI.scale(6)))

        root.add(sectionPanel("Tickets to Create") {
            addComponent(infoArea)
        })
        root.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Status / error row
        val statusRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusLabel)
            add(errorLabel)
        }
        root.add(statusRow)

        // Initial fields + info (before API response)
        rebuildFieldsPanel()
        refreshInfoArea()

        // Wire project search: debounce 400 ms then call API
        projectSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = scheduleSearch()
            override fun removeUpdate(e: DocumentEvent)  = scheduleSearch()
            override fun changedUpdate(e: DocumentEvent) = Unit

            private fun scheduleSearch() {
                searchDebounce?.stop()
                searchDebounce = Timer(400) {
                    val query = projectSearchField.text.trim()
                    // Trigger search if the user typed ≥ 2 chars, or cleared the field
                    if (query.length >= 2 || query.isEmpty()) loadProjectsAsync(query)
                }.also { it.isRepeats = false; it.start() }
            }
        })

        // Wire combos
        projectCombo.addActionListener {
            val proj = projectCombo.selectedItem as? JiraProject ?: return@addActionListener
            loadIssueTypesAsync(proj.key)
        }
        issueTypeCombo.addActionListener {
            val type = issueTypeCombo.selectedItem as? JiraIssueType ?: return@addActionListener
            val proj = projectCombo.selectedItem as? JiraProject     ?: return@addActionListener
            loadFieldMetaAsync(proj.key, type.id)
        }

        return JBScrollPane(root).apply {
            preferredSize = Dimension(JBUI.scale(580), JBUI.scale(540))
            border        = JBUI.Borders.empty()
        }
    }

    // ── Async data loaders ────────────────────────────────────────────────────

    private fun loadProjectsAsync(query: String = "") {
        val statusMsg = if (query.isBlank()) "Loading Jira projects…"
                        else "Searching projects for \"$query\"…"
        setStatus(statusMsg)
        projectCombo.isEnabled   = false
        issueTypeCombo.isEnabled = false
        isOKActionEnabled        = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projects = jiraClient.getProjects(query = query)
                onEdt {
                    projectCombo.removeAllItems()
                    projects.forEach { projectCombo.addItem(it) }
                    projectCombo.isEnabled = projects.isNotEmpty()
                    if (projects.isEmpty()) {
                        val hint = if (query.isNotBlank()) "No projects matching \"$query\"."
                                   else "No accessible Jira projects found."
                        showError(hint)
                    } else {
                        clearStatus()
                        projectCombo.selectedIndex = 0
                    }
                }
            } catch (e: JiraApiException) { onEdt { showError(e.userMessage()) } }
              catch (t: Throwable)         { onEdt { showError("${t::class.simpleName}: ${t.message}") } }
        }
    }

    private fun loadIssueTypesAsync(projectKey: String) {
        setStatus("Loading issue types for $projectKey…")
        issueTypeCombo.removeAllItems()
        issueTypeCombo.isEnabled = false
        isOKActionEnabled        = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val types = jiraClient.getIssueTypes(projectKey)
                onEdt {
                    issueTypeCombo.removeAllItems()
                    types.forEach { issueTypeCombo.addItem(it) }
                    issueTypeCombo.isEnabled = types.isNotEmpty()
                    // Pre-select the type matching settings
                    val preferred = types.firstOrNull {
                        it.name.equals(settings.jiraIssueType, ignoreCase = true)
                    }
                    issueTypeCombo.selectedItem = preferred ?: types.firstOrNull()
                    clearStatus()
                }
            } catch (e: JiraApiException) { onEdt { showError(e.userMessage()) } }
              catch (e: Exception)         { onEdt { showError("Failed to load issue types: ${e.message}") } }
        }
    }

    private fun loadFieldMetaAsync(projectKey: String, issueTypeId: String) {
        setStatus("Loading field definitions…")
        isOKActionEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val meta = try {
                jiraClient.getCreateMeta(projectKey, issueTypeId)
            } catch (e: Exception) {
                log.warn("getCreateMeta failed (non-blocking): ${e.message}")
                emptyList()
            }
            onEdt {
                rebuildFieldsPanel(meta)
                isOKActionEnabled = true
                clearStatus()
            }
        }
    }

    // ── Dynamic fields panel ──────────────────────────────────────────────────

    /**
     * Rebuilds [fieldsContainer] based on [meta] from the Jira createmeta API.
     *
     * Always shows labels and priority.
     * Adds extra required / simple fields discovered in [meta].
     * Skips fields handled internally (summary, description, project, issuetype…).
     */
    private fun rebuildFieldsPanel(meta: List<JiraFieldMeta> = emptyList()) {
        fieldsContainer.removeAll()
        extraInputs.clear()

        val alwaysSkip = setOf(
            "summary", "description", "project", "issuetype",
            "labels", "priority", "attachment", "comment",
            "worklog", "watches", "votes", "status", "resolution",
            "reporter", "created", "updated"
        )

        // Priority: use combo with allowed values if available, else text
        val priorityMeta = meta.firstOrNull { it.key == "priority" }
        usePriorityCombo = priorityMeta != null && priorityMeta.allowedValues.isNotEmpty()
        val priorityComponent: JComponent = if (usePriorityCombo) {
            priorityCombo.removeAllItems()
            priorityMeta!!.allowedValues.forEach { priorityCombo.addItem(it) }
            val match = (0 until priorityCombo.itemCount)
                .firstOrNull { priorityCombo.getItemAt(it).equals(settings.jiraPriority, ignoreCase = true) }
            if (match != null) priorityCombo.selectedIndex = match
            priorityCombo
        } else {
            priorityField
        }

        var row = 0
        addFieldRow("Labels:",   labelsField,        "comma-separated",            row++)
        addFieldRow("Priority:", priorityComponent,  null,                         row++)
        addFieldRow("SP:",
            JBLabel("Calculated per ticket — set automatically").apply { foreground = JBColor.GRAY },
            null, row++)

        // Extra fields from createmeta: required first, then optional simple ones
        val extras = meta
            .filter { it.key !in alwaysSkip && it.schema in setOf("string", "number", "array") }
            .sortedByDescending { it.required }
        for (field in extras) {
            val input = JBTextField(22)
            extraInputs[field.key] = input
            val hint = if (field.required) "required" else null
            addFieldRow("${field.name}:", input, hint, row++)
        }

        fieldsContainer.revalidate()
        fieldsContainer.repaint()
    }

    private fun addFieldRow(label: String, comp: JComponent, hint: String?, row: Int) {
        val gbc = GridBagConstraints()

        gbc.gridx   = 0; gbc.gridy  = row
        gbc.anchor  = GridBagConstraints.NORTHWEST
        gbc.fill    = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.insets  = Insets(JBUI.scale(4), JBUI.scale(4), JBUI.scale(2), JBUI.scale(8))
        fieldsContainer.add(JBLabel(label), gbc)

        gbc.gridx   = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        gbc.insets  = Insets(JBUI.scale(4), 0, JBUI.scale(2), JBUI.scale(4))
        fieldsContainer.add(comp, gbc)

        if (hint != null) {
            gbc.gridx   = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets  = Insets(JBUI.scale(5), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
            fieldsContainer.add(JBLabel(hint).apply {
                foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont()
            }, gbc)
        }
    }

    // ── Info area ─────────────────────────────────────────────────────────────

    private fun refreshInfoArea() {
        infoArea.text = buildString {
            appendLine("${issueGroups.size} ticket(s) — ${issueGroups.sumOf { it.issues.size }} issues total")
            appendLine()
            issueGroups.forEach { g ->
                val sp = StoryPointCalculator.format(g.storyPoints)
                appendLine(
                    "  Ticket ${g.batchIndex}/${g.totalBatches}" +
                    "  ${g.issueType.replace("_", " ").padEnd(18)}" +
                    "  ${g.issues.size} issues" +
                    "  $sp SP"
                )
            }
        }
    }

    // ── OK action — validate then expose results ───────────────────────────────

    override fun doOKAction() {
        val proj = projectCombo.selectedItem as? JiraProject
        val type = issueTypeCombo.selectedItem as? JiraIssueType
        if (proj == null || type == null) {
            showError("Please select a project and issue type.")
            return
        }

        // Validate required extra fields
        for ((key, input) in extraInputs) {
            if (input.text.isBlank()) {
                showError("Field '$key' is required.")
                input.requestFocus()
                return
            }
        }

        selectedProject   = proj
        selectedIssueType = type
        collectedFields   = buildCollectedFields()
        super.doOKAction()
    }

    private fun buildCollectedFields(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        val labelsList = labelsField.text.split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (labelsList.isNotEmpty()) result["labels"] = labelsList

        val priority = if (usePriorityCombo) priorityCombo.selectedItem?.toString()
                       else priorityField.text.trim()
        if (!priority.isNullOrBlank()) result["priority"] = mapOf("name" to priority)

        extraInputs.forEach { (key, input) ->
            val v = input.text.trim()
            if (v.isNotEmpty()) result[key] = v
        }
        return result
    }

    // ── Status / error helpers ────────────────────────────────────────────────

    private fun setStatus(msg: String) {
        statusLabel.text      = msg
        statusLabel.foreground = JBColor.GRAY
        statusLabel.isVisible = true
        errorLabel.isVisible  = false
    }

    private fun clearStatus() {
        statusLabel.isVisible = false
        errorLabel.isVisible  = false
    }

    private fun showError(msg: String) {
        errorLabel.text       = msg
        errorLabel.isVisible  = true
        statusLabel.isVisible = false
    }

    // ── Section panel DSL ─────────────────────────────────────────────────────

    private fun sectionPanel(title: String, content: SectionBuilder.() -> Unit): JPanel {
        val inner = JPanel(GridBagLayout())
        SectionBuilder(inner).content()

        val header = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints()
            gbc.gridx   = 0; gbc.anchor = GridBagConstraints.WEST
            gbc.fill    = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets  = Insets(0, 0, 0, JBUI.scale(8))
            add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, gbc)

            gbc.gridx   = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets  = Insets(0, 0, 0, 0)
            add(JSeparator(), gbc)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 4, 2, 4)
            add(header, BorderLayout.NORTH)
            add(inner,  BorderLayout.CENTER)
        }
    }

    private inner class SectionBuilder(private val panel: JPanel) {
        private var row = 0

        fun addRow(label: String, comp: JComponent) {
            val gbc = GridBagConstraints()
            gbc.gridx   = 0; gbc.gridy = row
            gbc.anchor  = GridBagConstraints.NORTHWEST
            gbc.fill    = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets  = Insets(JBUI.scale(4), JBUI.scale(4), JBUI.scale(2), JBUI.scale(8))
            panel.add(JBLabel(label), gbc)

            gbc.gridx   = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets  = Insets(JBUI.scale(4), 0, JBUI.scale(2), JBUI.scale(4))
            panel.add(comp, gbc)
            row++
        }

        fun addComponent(comp: JComponent) {
            val gbc = GridBagConstraints()
            gbc.gridx     = 0; gbc.gridy = row++
            gbc.gridwidth = 2
            gbc.fill      = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets    = Insets(JBUI.scale(4), JBUI.scale(4), JBUI.scale(4), JBUI.scale(4))
            panel.add(comp, gbc)
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block)
}
