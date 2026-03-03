package com.sonarjiraissuer.plugin.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sonarjiraissuer.plugin.api.JiraApiException
import com.sonarjiraissuer.plugin.api.JiraClient
import com.sonarjiraissuer.plugin.api.JiraIssueType
import com.sonarjiraissuer.plugin.api.JiraProject
import com.sonarjiraissuer.plugin.api.JiraSprint
import com.sonarjiraissuer.plugin.api.SonarApiException
import com.sonarjiraissuer.plugin.api.SonarProject
import com.sonarjiraissuer.plugin.api.SonarQubeClient
import com.sonarjiraissuer.plugin.api.dto.SonarIssue
import com.sonarjiraissuer.plugin.model.IssueGroup
import com.sonarjiraissuer.plugin.model.IssueType
import com.sonarjiraissuer.plugin.model.JiraFormatterConfig
import com.sonarjiraissuer.plugin.service.FileExportService
import com.sonarjiraissuer.plugin.service.IssueGroupingService
import com.sonarjiraissuer.plugin.service.JiraDescriptionBuilder
import com.sonarjiraissuer.plugin.service.JiraIssueMappingService
import com.sonarjiraissuer.plugin.service.JiraTextFormatter
import com.sonarjiraissuer.plugin.service.StoryPointCalculator
import com.sonarjiraissuer.plugin.settings.PluginSettings
import com.sonarjiraissuer.plugin.util.NotificationHelper
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SonarJiraPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log      = Logger.getInstance(SonarJiraPanel::class.java)
    private val settings = PluginSettings.getInstance()

    // ── Top bar ────────────────────────────────────────────────────────────────
    private val sonarConnLabel = JBLabel().apply { font = JBUI.Fonts.smallFont() }
    private val jiraConnLabel  = JBLabel().apply { font = JBUI.Fonts.smallFont() }
    private val configureButton = JButton("⚙  Configure").apply {
        toolTipText = "Set SonarQube and Jira URLs and tokens"
        font        = JBUI.Fonts.smallFont()
    }

    // ── SonarQube Project ──────────────────────────────────────────────────────
    private val projectKeyField     = JBTextField(settings.projectKey, 30)
    private val browseProjectButton = JButton("Browse…")

    // ── Issue Types ────────────────────────────────────────────────────────────
    private val issueTypeBoxes: Map<IssueType, JCheckBox> =
        IssueType.values().associateWith { type ->
            JCheckBox(type.displayName, type.apiValue in settings.selectedIssueTypes)
        }

    // ── Load ───────────────────────────────────────────────────────────────────
    private val loadButton  = JButton("▶  Load Issues")
    private val statusLabel = JBLabel("Not loaded.").apply { font = JBUI.Fonts.smallFont() }
    private var loadedIssues: List<SonarIssue> = emptyList()

    // ── Generation Settings ────────────────────────────────────────────────────
    private val batchSizeField    = JBTextField(settings.batchSize.toString(), 8)
    private val titlePrefixField  = JBTextField(settings.titlePrefix, 28)
    private val jiraPriorityField = JBTextField(settings.jiraPriority, 15)
    private val jiraLabelsField   = JBTextField(settings.jiraLabels, 35)
    private val outputFolderField = TextFieldWithBrowseButton()

    // ── Jira Target ────────────────────────────────────────────────────────────
    private val epicLinkField = JBTextField(settings.epicLink, 15).apply {
        toolTipText = "Optional — issue key of the Epic to link tickets to (e.g. PROJ-42).\n" +
                      "Uses 'customfield_10014' (Classic projects) or 'parent' (Next-Gen).\n" +
                      "Leave blank to create tickets without an epic link."
    }

    private val jiraProjectCombo = ComboBox<JiraProject>().apply {
        isEnabled = false
        renderer  = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, focus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, isSelected, focus).also {
                (it as? javax.swing.JLabel)?.text =
                    if (value is JiraProject) "${value.key}  —  ${value.name}" else ""
            }
        }
    }
    private val jiraIssueTypeCombo = ComboBox<JiraIssueType>().apply {
        isEnabled = false
        renderer  = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, focus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, isSelected, focus).also {
                (it as? javax.swing.JLabel)?.text = (value as? JiraIssueType)?.name ?: ""
            }
        }
    }
    private val refreshProjectsBtn = JButton("⟳").apply {
        isEnabled   = false
        toolTipText = "Reload Jira projects"
    }
    private val jiraTargetStatus = JBLabel("").apply {
        font       = JBUI.Fonts.smallFont()
        foreground = JBColor.GRAY
    }

    // Sentinel sprint that means "don't assign to any sprint" (put in backlog).
    private val NO_SPRINT = JiraSprint(id = -1, name = "Backlog (no sprint)", state = "none")

    private val sprintCombo = ComboBox<JiraSprint>().apply {
        isEnabled = false
        addItem(NO_SPRINT)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, focus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, isSelected, focus).also {
                val s = value as? JiraSprint
                (it as? javax.swing.JLabel)?.text = when {
                    s == null      -> ""
                    s.id == -1     -> s.name
                    s.state == "active" -> "▶ ${s.name}"
                    else           -> "○ ${s.name}"
                }
            }
        }
    }
    private val sprintStatusLabel = JBLabel("").apply {
        font       = JBUI.Fonts.smallFont()
        foreground = JBColor.GRAY
    }

    // ── Custom fields (populated from Jira create metadata) ────────────────────
    // Each entry is (fieldKey, schemaType, textField). Filled when issue type is selected.
    private val customFieldInputs   = mutableListOf<Triple<String, String, JBTextField>>()
    // Auto-detected story-points field key (falls back to customfield_10016 if not found).
    private var storyPointsFieldKey = "customfield_10016"
    // Fields managed internally — excluded from the custom fields editor.
    private val managedFieldKeys    = setOf(
        "summary", "description", "project", "issuetype",
        "labels", "priority", "reporter", "assignee",
        "customfield_10014",  // Epic Link (classic)
        "customfield_10020",  // Sprint
        "parent"              // Epic parent / epic link (Next-Gen)
    )

    private val customFieldsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val customFieldsScroll = JBScrollPane(customFieldsPanel).apply {
        preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(100))
        maximumSize   = Dimension(Int.MAX_VALUE, JBUI.scale(100))
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.customLine(JBColor.border())
    }

    // ── Preview ────────────────────────────────────────────────────────────────
    private val previewArea = JTextArea(12, 60).apply {
        isEditable = false
        font       = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11f).toInt())
        lineWrap   = false
        tabSize    = 2
    }

    // ── Export ─────────────────────────────────────────────────────────────────
    private val generateButton     = JButton("⬇  Generate File").apply  { isEnabled = false }
    private val createInJiraButton = JButton("⬆  Create in Jira").apply { isEnabled = false }

    // ──────────────────────────────────────────────────────────────────────────

    init {
        border = JBUI.Borders.empty(8)
        configureOutputFolder()
        buildLayout()
        wireListeners()
        refreshConnectionStatus()
        // Auto-load Jira projects if already configured
        if (settings.jiraServerUrl.isNotBlank() && settings.loadJiraToken().isNotBlank()) {
            loadJiraProjectsAsync()
        }
    }

    // ── Layout ─────────────────────────────────────────────────────────────────

    private fun buildLayout() {
        add(buildTopBar(), BorderLayout.NORTH)

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2)

            add(section("SonarQube Project") {
                val row = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(projectKeyField,     BorderLayout.CENTER)
                    add(browseProjectButton, BorderLayout.EAST)
                }
                row("Project Key:", row, "Use Browse… or type directly")
                val cbRow = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    issueTypeBoxes.values.forEach { cb -> add(cb); add(Box.createHorizontalStrut(JBUI.scale(12))) }
                    add(Box.createHorizontalGlue())
                }
                row("Issue Types:", cbRow, null)
            })

            add(vspace())

            add(section("Load Issues") {
                val row = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(loadButton)
                    add(Box.createHorizontalStrut(JBUI.scale(12)))
                    add(statusLabel)
                    add(Box.createHorizontalGlue())
                }
                component(row)
            })

            add(vspace())

            add(section("Generation Settings") {
                row("Issues per ticket:", batchSizeField,    "SonarQube issues grouped into each ticket")
                row("Title prefix:",      titlePrefixField,  "Prepended to every ticket summary")
                row("Priority:",          jiraPriorityField, "e.g. Medium, High, Critical")
                row("Labels (CSV):",      jiraLabelsField,   "Comma-separated Jira labels")
                row("Output folder:",     outputFolderField, null)
            })

            add(vspace())

            add(section("Jira Target") {
                val projectRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(jiraProjectCombo, BorderLayout.CENTER)
                    add(refreshProjectsBtn, BorderLayout.EAST)
                }
                val projectCol = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(projectRow.also  { it.alignmentX = LEFT_ALIGNMENT })
                    add(jiraTargetStatus.also { it.alignmentX = LEFT_ALIGNMENT })
                }
                row("Project:",    projectCol,         null)
                row("Issue Type:", jiraIssueTypeCombo, null)
                row("Fields:",     customFieldsScroll, "Optional — leave blank to skip")
                val sprintCol = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(sprintCombo.also      { it.alignmentX = LEFT_ALIGNMENT })
                    add(sprintStatusLabel.also { it.alignmentX = LEFT_ALIGNMENT })
                }
                row("Sprint:", sprintCol, "▶ active   ○ future")
                row("Epic Link:",  epicLinkField,      "Optional — e.g. PROJ-42")
            })

            add(vspace())

            add(section("Preview") {
                component(JBScrollPane(previewArea).apply {
                    preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
                    maximumSize   = Dimension(Int.MAX_VALUE, JBUI.scale(200))
                })
            })

            add(vspace())

            add(section("Export") {
                val exportRow = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(generateButton)
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(createInJiraButton)
                    add(Box.createHorizontalGlue())
                }
                component(exportRow)
            })

            add(Box.createVerticalGlue())
        }

        add(JBScrollPane(body), BorderLayout.CENTER)
    }

    private fun buildTopBar(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(5, 8, 5, 8)
            )
            add(sonarConnLabel)
            add(Box.createHorizontalStrut(JBUI.scale(20)))
            add(jiraConnLabel)
            add(Box.createHorizontalGlue())
            add(configureButton)
        }

    private fun vspace() = Box.createVerticalStrut(JBUI.scale(4))

    // ── Top bar status ─────────────────────────────────────────────────────────

    fun refreshConnectionStatus() {
        fun hostOf(url: String) = url.removePrefix("https://").removePrefix("http://").trimEnd('/')

        val sonarUrl = settings.serverUrl
        if (sonarUrl.isNotBlank()) {
            sonarConnLabel.text      = "SonarQube: ✓ ${hostOf(sonarUrl)}"
            sonarConnLabel.foreground = JBColor(0x2E7D32, 0x4CAF50)
        } else {
            sonarConnLabel.text      = "SonarQube: not configured"
            sonarConnLabel.foreground = JBColor.GRAY
        }

        val jiraUrl = settings.jiraServerUrl
        if (jiraUrl.isNotBlank()) {
            jiraConnLabel.text      = "Jira: ✓ ${hostOf(jiraUrl)}"
            jiraConnLabel.foreground = JBColor(0x2E7D32, 0x4CAF50)
        } else {
            jiraConnLabel.text      = "Jira: not configured"
            jiraConnLabel.foreground = JBColor.GRAY
        }
    }

    // ── Listeners ──────────────────────────────────────────────────────────────

    private fun wireListeners() {
        configureButton.addActionListener    { openConfigure() }
        loadButton.addActionListener         { onLoad() }
        generateButton.addActionListener     { onGenerate() }
        createInJiraButton.addActionListener { onCreateInJira() }
        browseProjectButton.addActionListener { onBrowseProjects() }
        refreshProjectsBtn.addActionListener { loadJiraProjectsAsync() }

        // Project combo → persist selection + populate issue types + load sprints
        jiraProjectCombo.addActionListener {
            val proj = jiraProjectCombo.selectedItem as? JiraProject ?: return@addActionListener
            settings.jiraProjectKey = proj.key
            if (proj.issueTypes.isNotEmpty()) {
                jiraIssueTypeCombo.removeAllItems()
                proj.issueTypes.forEach { jiraIssueTypeCombo.addItem(it) }
                jiraIssueTypeCombo.isEnabled = true
                val preferred = proj.issueTypes.firstOrNull { it.id == settings.jiraIssueTypeId }
                    ?: proj.issueTypes.firstOrNull { it.name.equals(settings.jiraIssueType, ignoreCase = true) }
                if (preferred != null) jiraIssueTypeCombo.selectedItem = preferred
            } else {
                loadJiraIssueTypesAsync(proj.key)
            }
            loadJiraSprintsAsync(proj.key)
        }

        // Issue type combo → persist selection + load available fields for this project/type
        jiraIssueTypeCombo.addActionListener {
            val type = jiraIssueTypeCombo.selectedItem as? JiraIssueType ?: return@addActionListener
            settings.jiraIssueTypeId = type.id
            val proj = jiraProjectCombo.selectedItem as? JiraProject ?: return@addActionListener
            loadCustomFieldsAsync(proj.key, type.id)
        }

        batchSizeField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = refreshPreview()
            override fun removeUpdate(e: DocumentEvent)  = refreshPreview()
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
    }

    // ── Configure button ───────────────────────────────────────────────────────

    private fun openConfigure() {
        ConnectionSetupDialog(project).showAndGet()   // return value intentionally ignored
        refreshConnectionStatus()                     // always refresh — Test button saves without OK
        if (settings.jiraServerUrl.isNotBlank() && settings.loadJiraToken().isNotBlank()) {
            loadJiraProjectsAsync()
        }
    }

    // ── Browse SonarQube Projects ──────────────────────────────────────────────

    private fun onBrowseProjects() {
        val url   = settings.serverUrl
        val token = settings.loadSavedToken()
        if (url.isBlank())   { alert("SonarQube not configured — click ⚙ Configure first."); return }
        if (token.isBlank()) { alert("SonarQube token not saved — click ⚙ Configure first."); return }

        browseProjectButton.isEnabled = false
        ProgressManager.getInstance().run(object : Task.Modal(project, "Loading SonarQube Projects", false) {
            private var projects: List<SonarProject> = emptyList()
            private var error: String? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true; indicator.text = "Fetching accessible projects…"
                try   { projects = SonarQubeClient(url, token).searchProjects() }
                catch (e: SonarApiException) { error = e.userMessage() }
                catch (e: Exception)         { error = "Failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            override fun onSuccess() {
                browseProjectButton.isEnabled = true
                val err = error
                if (err != null) { NotificationHelper.notifyError(project, "Project Load Failed", err); return }
                if (projects.isEmpty()) { alert("No projects found.\n\nVerify token permissions."); return }
                val dialog = ProjectBrowseDialog(project, projects)
                if (dialog.showAndGet()) {
                    dialog.selectedProject?.let { proj ->
                        projectKeyField.text = proj.key
                        settings.projectKey  = proj.key
                    }
                }
            }
        })
    }

    // ── Load Issues ────────────────────────────────────────────────────────────

    private fun onLoad() {
        val url        = settings.serverUrl
        val token      = settings.loadSavedToken()
        val projectKey = projectKeyField.text.trim()
        val types      = selectedTypes()

        if (url.isBlank())        { alert("SonarQube not configured — click ⚙ Configure first."); return }
        if (token.isBlank())      { alert("SonarQube token not saved — click ⚙ Configure first."); return }
        if (projectKey.isEmpty()) { alert("Project Key is required.\nUse Browse… to discover it."); return }
        if (types.isEmpty())      { alert("Select at least one issue type."); return }

        settings.projectKey         = projectKey
        settings.selectedIssueTypes = types
        setStatus("Loading issues…", JBColor.foreground())
        loadButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading SonarQube Issues", true) {
            private var result: List<SonarIssue> = emptyList()
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false; indicator.fraction = 0.05
                indicator.text = "Connecting to SonarQube…"
                result = SonarQubeClient(url, token)
                    .fetchAllIssues(projectKey = projectKey, issueTypes = types) { fetched, total ->
                        if (indicator.isCanceled) throw InterruptedException("Cancelled by user")
                        indicator.fraction = if (total > 0) fetched.toDouble() / total else 0.5
                        indicator.text     = "Loaded $fetched / $total issues…"
                    }
            }
            override fun onSuccess() {
                loadedIssues                 = result
                loadButton.isEnabled         = true
                generateButton.isEnabled     = result.isNotEmpty()
                createInJiraButton.isEnabled = result.isNotEmpty()
                setStatus(buildIssueSummary(result),
                    if (result.isNotEmpty()) JBColor(0x2E7D32, 0x4CAF50) else JBColor.foreground())
                refreshPreview()
                log.info("Loaded ${result.size} issues from '$projectKey'")
            }
            override fun onThrowable(error: Throwable) {
                loadButton.isEnabled = true
                val msg = when (error) {
                    is SonarApiException    -> error.userMessage()
                    is InterruptedException -> "Load cancelled."
                    else                    -> "Unexpected error: ${error.message ?: error.javaClass.simpleName}"
                }
                setStatus("✗ $msg", JBColor.RED)
                NotificationHelper.notifyError(project, "Load Failed", msg)
            }
        })
    }

    // ── Create Tickets in Jira ─────────────────────────────────────────────────

    private fun onCreateInJira() {
        if (loadedIssues.isEmpty()) { alert("No issues loaded. Click '▶ Load Issues' first."); return }

        val jiraUrl   = settings.jiraServerUrl
        val jiraToken = settings.loadJiraToken()
        if (jiraUrl.isBlank())   { alert("Jira not configured — click ⚙ Configure first."); return }
        if (jiraToken.isBlank()) { alert("Jira token not saved — click ⚙ Configure first."); return }

        val jiraProject = jiraProjectCombo.selectedItem as? JiraProject
            ?: run { alert("No Jira project selected. Load projects using ⟳."); return }
        val jiraIssueType = jiraIssueTypeCombo.selectedItem as? JiraIssueType
            ?: run { alert("No issue type selected. Select a project first."); return }

        val batchSize = batchSizeField.text.toIntOrNull()?.takeIf { it > 0 } ?: settings.batchSize
        val allGroups = IssueGroupingService.groupByType(loadedIssues, batchSize).values.flatten()
        val baseDir   = settings.outputFolder.ifEmpty { project.basePath ?: System.getProperty("user.home") }
        val mapFile   = JiraIssueMappingService.resolveMapFile(baseDir, settings.projectKey)

        val allSonarKeys = allGroups.flatMap { g -> g.issues.map { it.key } }

        createInJiraButton.isEnabled = false
        setStatus("Checking existing Jira tickets…", JBColor.foreground())

        // Step 1 — query Jira by sq-<issueKey> labels for any team member's prior tickets.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking existing Jira tickets", false) {
            private var mergedMap: Map<String, String> = emptyMap()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Looking for existing SonarQube tickets in Jira…"
                val localMap = JiraIssueMappingService.readMappings(mapFile)
                val jiraMap  = try {
                    JiraClient(jiraUrl, jiraToken, settings.jiraEmail)
                        .findSonarMappings(jiraProject.key, allSonarKeys)
                } catch (t: Throwable) {
                    log.warn("Jira deduplication check failed — using local cache only: ${t.message}")
                    emptyMap()
                }
                mergedMap = localMap + jiraMap   // Jira is the authoritative source
                try {
                    if (jiraMap.isNotEmpty()) JiraIssueMappingService.addMappings(mapFile, jiraMap)
                } catch (t: Throwable) {
                    log.warn("Could not update local map cache: ${t.message}")
                }
            }

            override fun onSuccess() =
                showSelectionDialog(allGroups, mergedMap, mapFile, jiraProject, jiraIssueType, jiraUrl, jiraToken)

            override fun onThrowable(error: Throwable) {
                createInJiraButton.isEnabled = true
                setStatus("", JBColor.foreground())
                log.warn("Deduplication task threw unexpectedly: ${error.message}")
            }
        })
    }

    // Step 2 — show which groups are new vs already ticketed, then create.
    // Called on the EDT from onSuccess() of the deduplication background task.
    private fun showSelectionDialog(
        allGroups:     List<IssueGroup>,
        existingMap:   Map<String, String>,
        mapFile:       String,
        jiraProject:   JiraProject,
        jiraIssueType: JiraIssueType,
        jiraUrl:       String,
        jiraToken:     String
    ) {
        createInJiraButton.isEnabled = true
        setStatus("", JBColor.foreground())

        val jiraKeyByGroup: Map<IssueGroup, String> = allGroups
            .filter { group -> group.issues.all { it.key in existingMap } }
            .associateWith { group -> existingMap.getValue(group.issues.first().key) }

        val selectionDialog = SelectTicketsDialog(project, allGroups, jiraKeyByGroup)
        if (!selectionDialog.showAndGet()) return
        val chosenGroups = selectionDialog.selectedGroups.takeIf { it.isNotEmpty() } ?: return

        val userLabels     = jiraLabelsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // SONAR_SYNC_LABEL makes the ticket findable by JQL; sq-<key> labels per issue
        // are added at creation time (see below) so findSonarMappings() can resolve them directly.
        val priority       = jiraPriorityField.text.trim()
        val epicKey        = epicLinkField.text.trim().also { settings.epicLink = it }
        val selectedSprint = sprintCombo.selectedItem as? JiraSprint

        // Collect non-empty values from the custom fields editor.
        // schema "number" → Double, "array" → List<String>, everything else → String.
        val extraFields: Map<String, Any> = customFieldInputs.mapNotNull { (key, schema, tf) ->
            val text = tf.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val value: Any = when (schema) {
                "number" -> text.toDoubleOrNull() ?: text
                "array"  -> text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                else     -> text
            }
            key to value
        }.toMap()

        // labels are built per-group at creation time (user labels + sync label + sq-<key> per issue)
        val sharedFields = mutableMapOf<String, Any>()
        if (priority.isNotEmpty())                     sharedFields["priority"]        = mapOf("name" to priority)
        if (epicKey.isNotBlank())                      sharedFields[epicLinkFieldId]   = epicKey
        if (extraFields.isNotEmpty())                  sharedFields.putAll(extraFields)
        // Sprint: only set when the user picked an actual sprint (not the Backlog sentinel)
        if (selectedSprint != null && selectedSprint.id != -1) {
            sharedFields["customfield_10020"] = selectedSprint.id
        }

        createInJiraButton.isEnabled = false
        setStatus("Creating ${chosenGroups.size} Jira ticket(s)…", JBColor.foreground())

        // Step 3 — create the selected tickets
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Jira Tickets", false) {
            private val createdKeys = mutableListOf<String>()
            private var errorMsg: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val client = JiraClient(jiraUrl, jiraToken, settings.jiraEmail)
                val config = buildFormatterConfig()

                chosenGroups.forEachIndexed { idx, group ->
                    if (indicator.isCanceled) return
                    indicator.fraction = idx.toDouble() / chosenGroups.size
                    indicator.text     = "Creating ticket ${idx + 1} / ${chosenGroups.size}…"

                    val typeLabel   = group.issueType.replace("_", " ")
                    val summary     = "${config.titlePrefix} $typeLabel — batch ${group.batchIndex}/${group.totalBatches} — ${group.issues.size} issues to fix"
                    val description = JiraDescriptionBuilder.build(group, config, client.isCloud)
                    // One sq-<issueKey> label per SonarQube issue — used by findSonarMappings()
                    // for team-safe deduplication without any description parsing.
                    val issueLabels = group.issues.map { JiraClient.sonarIssueLabel(it.key) }
                    val allLabels   = (userLabels + JiraClient.SONAR_SYNC_LABEL + issueLabels).distinct()
                    val fields      = sharedFields.toMutableMap().also {
                        it["labels"]            = allLabels
                        it[storyPointsFieldKey] = group.storyPoints
                    }

                    try {
                        val key = client.createIssue(jiraProject.key, jiraIssueType.id, summary, description, fields)
                        createdKeys.add(key)
                        JiraIssueMappingService.addMappings(mapFile, group.issues.associate { it.key to key })
                        log.info("Created $key for ${group.issueType} batch ${group.batchIndex}")
                    } catch (e: JiraApiException) {
                        errorMsg = "Ticket ${idx + 1}/${chosenGroups.size} failed: ${e.userMessage()}"; return
                    }
                }
            }

            override fun onSuccess() {
                createInJiraButton.isEnabled = true
                val err = errorMsg
                if (err != null) {
                    setStatus("✗ $err", JBColor.RED)
                    NotificationHelper.notifyError(project, "Jira Creation Partially Failed", err)
                } else {
                    val skipped = allGroups.size - chosenGroups.size
                    val msg     = "${createdKeys.size} ticket(s) created: ${createdKeys.joinToString(", ")}" +
                                  if (skipped > 0) "   ($skipped skipped)" else ""
                    setStatus("✓ $msg", JBColor(0x2E7D32, 0x4CAF50))
                    NotificationHelper.notifyInfo(project, "Jira Tickets Created", msg)
                }
            }

            override fun onThrowable(error: Throwable) {
                createInJiraButton.isEnabled = true
                val msg = "Creation failed: ${error.message ?: error.javaClass.simpleName}"
                setStatus("✗ $msg", JBColor.RED)
                NotificationHelper.notifyError(project, "Jira Creation Failed", msg)
            }
        })
    }

    // ── Generate / Export ──────────────────────────────────────────────────────

    private fun onGenerate() {
        if (loadedIssues.isEmpty()) { alert("No issues loaded. Click '▶ Load Issues' first."); return }
        val outputFolder = outputFolderField.text.trim()
        if (outputFolder.isEmpty()) { alert("Output folder is required."); return }
        val batchSize = batchSizeField.text.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: run { alert("'Issues per ticket' must be a positive integer."); return }

        persistGenerationSettings(batchSize)
        generateButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Jira Ticket File", false) {
            private var filePath  = ""
            private var newCount  = 0
            private var skipCount = 0
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                filePath = FileExportService.resolveFilePath(outputFolder, settings.projectKey)
                val alreadyExported = FileExportService.readExportedKeys(filePath)
                val newIssues = loadedIssues.filter { it.key !in alreadyExported }
                skipCount = loadedIssues.size - newIssues.size
                newCount  = newIssues.size
                if (newIssues.isEmpty()) return
                val allGroups = IssueGroupingService.groupByType(newIssues, batchSize).values.flatten()
                val content   = JiraTextFormatter(buildFormatterConfig()).format(allGroups, alreadyExported.isNotEmpty())
                FileExportService.append(content, filePath)
                ApplicationManager.getApplication().invokeLater {
                    previewArea.text = content; previewArea.caretPosition = 0
                }
            }
            override fun onSuccess() {
                generateButton.isEnabled = true
                if (newCount == 0) {
                    NotificationHelper.notifyInfo(project, "Already Up to Date",
                        "All ${loadedIssues.size} loaded issues are already in the file.")
                    setStatus("All issues already exported.", JBColor.foreground())
                } else {
                    val msg = "$newCount issue(s) exported → $filePath" +
                              if (skipCount > 0) "  ($skipCount already exported, skipped)" else ""
                    NotificationHelper.notifyInfo(project, "Jira Tickets Exported", msg)
                    setStatus("✓ $msg", JBColor(0x2E7D32, 0x4CAF50))
                }
            }
            override fun onThrowable(error: Throwable) {
                generateButton.isEnabled = true
                val msg = "Export failed: ${error.message ?: error.javaClass.simpleName}"
                NotificationHelper.notifyError(project, "Export Failed", msg)
                setStatus("✗ $msg", JBColor.RED)
            }
        })
    }

    // ── Jira: load projects & issue types ─────────────────────────────────────

    private fun loadJiraProjectsAsync() {
        val url   = settings.jiraServerUrl
        val email = settings.jiraEmail
        val token = settings.loadJiraToken()

        if (url.isBlank() || token.isBlank()) {
            jiraTargetStatus.text      = "Click ⚙ Configure to connect Jira."
            jiraTargetStatus.foreground = JBColor.GRAY
            return
        }

        jiraProjectCombo.isEnabled  = false
        jiraIssueTypeCombo.isEnabled = false
        refreshProjectsBtn.isEnabled = false
        jiraTargetStatus.text        = "Loading projects…"
        jiraTargetStatus.foreground  = JBColor.GRAY

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projects = JiraClient(url, token, email).getProjects()
                ApplicationManager.getApplication().invokeLater {
                    jiraProjectCombo.removeAllItems()
                    projects.forEach { jiraProjectCombo.addItem(it) }
                    jiraProjectCombo.isEnabled   = projects.isNotEmpty()
                    refreshProjectsBtn.isEnabled = true
                    if (projects.isEmpty()) {
                        jiraTargetStatus.text      = "No accessible projects found."
                        jiraTargetStatus.foreground = JBColor.RED
                    } else {
                        jiraTargetStatus.text      = "${projects.size} project(s) loaded."
                        jiraTargetStatus.foreground = JBColor(0x2E7D32, 0x4CAF50)
                        val saved = projects.indexOfFirst { it.key == settings.jiraProjectKey }
                        jiraProjectCombo.selectedIndex = if (saved >= 0) saved else 0
                    }
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    refreshProjectsBtn.isEnabled = true
                    jiraTargetStatus.text        = "Failed: ${t.message?.take(80)}"
                    jiraTargetStatus.foreground  = JBColor.RED
                }
            }
        }
    }

    private fun loadJiraIssueTypesAsync(projectKey: String) {
        val url   = settings.jiraServerUrl
        val email = settings.jiraEmail
        val token = settings.loadJiraToken()
        if (url.isBlank() || token.isBlank()) return

        jiraIssueTypeCombo.removeAllItems()
        jiraIssueTypeCombo.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val types = JiraClient(url, token, email).getIssueTypes(projectKey)
                ApplicationManager.getApplication().invokeLater {
                    types.forEach { jiraIssueTypeCombo.addItem(it) }
                    jiraIssueTypeCombo.isEnabled = types.isNotEmpty()
                    val preferred = types.firstOrNull { it.id == settings.jiraIssueTypeId }
                        ?: types.firstOrNull { it.name.equals(settings.jiraIssueType, ignoreCase = true) }
                    if (preferred != null) jiraIssueTypeCombo.selectedItem = preferred
                }
            } catch (t: Throwable) {
                log.warn("loadJiraIssueTypesAsync failed for $projectKey: ${t.message}")
            }
        }
    }

    private fun loadJiraSprintsAsync(projectKey: String) {
        val url   = settings.jiraServerUrl
        val email = settings.jiraEmail
        val token = settings.loadJiraToken()
        if (url.isBlank() || token.isBlank()) return

        sprintCombo.isEnabled    = false
        sprintStatusLabel.text   = "Loading sprints…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val sprints = try {
                JiraClient(url, token, email).getActiveAndFutureSprints(projectKey)
            } catch (t: Throwable) {
                log.warn("loadJiraSprintsAsync failed for $projectKey: ${t.message}")
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                sprintCombo.removeAllItems()
                sprintCombo.addItem(NO_SPRINT)
                sprints.forEach { sprintCombo.addItem(it) }
                sprintCombo.isEnabled  = true
                sprintCombo.selectedIndex = 0   // default: Backlog
                sprintStatusLabel.text = when {
                    sprints.isEmpty() -> "No active/future sprints — ticket will go to backlog."
                    else              -> "${sprints.count { it.state == "active" }} active, " +
                                        "${sprints.count { it.state == "future" }} future sprint(s) found."
                }
            }
        }
    }

    /**
     * Loads Jira create-metadata for [projectKey] / [issueTypeId] on a background thread,
     * then populates [customFieldsPanel] with one text input per optional field.
     *
     * The story-points field is auto-detected by name and stored in [storyPointsFieldKey]
     * so it can be set automatically at ticket-creation time without being shown in the editor
     * (its value is calculated from the grouped issues, not entered manually).
     */
    private fun loadCustomFieldsAsync(projectKey: String, issueTypeId: String) {
        val url   = settings.jiraServerUrl
        val email = settings.jiraEmail
        val token = settings.loadJiraToken()
        if (url.isBlank() || token.isBlank()) return

        // Show a loading placeholder while the background call runs
        customFieldsPanel.removeAll()
        customFieldInputs.clear()
        customFieldsPanel.add(
            JBLabel("  Loading fields…").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }
        )
        customFieldsPanel.revalidate(); customFieldsPanel.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            val fields = try {
                JiraClient(url, token, email).getCreateMeta(projectKey, issueTypeId)
            } catch (t: Throwable) {
                log.warn("loadCustomFieldsAsync: getCreateMeta failed: ${t.message}")
                emptyList()
            }

            // Auto-detect story points field by name so the correct key is used at creation time.
            val storyPointsNames = setOf("story points", "story point estimate", "story point")
            val spField = fields.firstOrNull { it.name.lowercase() in storyPointsNames }
            val detectedSpKey = spField?.key ?: "customfield_10016"

            // Show only optional fields that aren't already handled by the plugin.
            val displayFields = fields.filter { f ->
                !f.required && f.key !in managedFieldKeys && f.key != detectedSpKey
            }

            ApplicationManager.getApplication().invokeLater {
                storyPointsFieldKey = detectedSpKey
                customFieldsPanel.removeAll()
                customFieldInputs.clear()

                if (displayFields.isEmpty()) {
                    customFieldsPanel.add(
                        JBLabel("  No optional fields available for this issue type.").apply {
                            foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont()
                        }
                    )
                } else {
                    displayFields.forEach { field ->
                        val input = JBTextField(20)
                        customFieldInputs.add(Triple(field.key, field.schema, input))
                        val row = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                            isOpaque = false
                            border   = JBUI.Borders.empty(1, 4, 1, 4)
                            val lbl  = JBLabel("${field.name}:").apply {
                                preferredSize = Dimension(JBUI.scale(130), preferredSize.height)
                                font          = JBUI.Fonts.smallFont()
                            }
                            add(lbl, BorderLayout.WEST)
                            add(input, BorderLayout.CENTER)
                            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
                        }
                        customFieldsPanel.add(row)
                    }
                }
                customFieldsPanel.revalidate(); customFieldsPanel.repaint()
            }
        }
    }

    // ── Preview ────────────────────────────────────────────────────────────────

    private fun refreshPreview() {
        if (loadedIssues.isEmpty()) return
        val batchSize = batchSizeField.text.toIntOrNull()?.takeIf { it > 0 } ?: settings.batchSize
        val allGroups = IssueGroupingService.groupByType(loadedIssues, batchSize).values.flatten()
        val text = buildString {
            appendLine("Preview: ${allGroups.size} Jira ticket(s) will be generated"); appendLine()
            allGroups.forEach { g ->
                appendLine("  [Ticket ${g.batchIndex}/${g.totalBatches}]  " +
                    "${g.issueType.padEnd(20)}  ${g.issues.size} issues   ${StoryPointCalculator.format(g.storyPoints)} SP")
            }
        }
        ApplicationManager.getApplication().invokeLater { previewArea.text = text }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    // customfield_10014 = "Epic Link" — standard field on both Jira Cloud (classic)
    // and Jira Server/DC.  Next-Gen / team-managed projects use the "parent" field
    // instead; those users should link the epic manually after creation.
    private val epicLinkFieldId = "customfield_10014"

    private fun selectedTypes(): List<String> =
        issueTypeBoxes.entries.filter { (_, cb) -> cb.isSelected }.map { (t, _) -> t.apiValue }

    private fun buildIssueSummary(issues: List<SonarIssue>): String {
        if (issues.isEmpty()) return "No issues found matching the selected filters."
        val byType = issues.groupBy { it.type }.entries.sortedBy { it.key }
            .joinToString("  |  ") { "${it.key}: ${it.value.size}" }
        return "${issues.size} issue(s) loaded  —  $byType"
    }

    private fun buildFormatterConfig() = JiraFormatterConfig(
        projectKey    = settings.projectKey,
        serverUrl     = settings.serverUrl,
        titlePrefix   = titlePrefixField.text.trim(),
        jiraIssueType = (jiraIssueTypeCombo.selectedItem as? JiraIssueType)?.name ?: settings.jiraIssueType,
        jiraPriority  = jiraPriorityField.text.trim(),
        jiraLabels    = jiraLabelsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    )

    private fun setStatus(text: String, color: Color) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text       = text
            statusLabel.foreground = color
        }
    }

    private fun persistGenerationSettings(batchSize: Int) {
        settings.batchSize    = batchSize
        settings.titlePrefix  = titlePrefixField.text.trim()
        settings.jiraPriority = jiraPriorityField.text.trim()
        settings.jiraLabels   = jiraLabelsField.text.trim()
        settings.outputFolder = outputFolderField.text.trim()
    }

    private fun configureOutputFolder() {
        outputFolderField.text = settings.outputFolder.ifEmpty {
            project.basePath ?: System.getProperty("user.home") ?: ""
        }
        outputFolderField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Output Folder")
                .withDescription("Choose where the generated ticket file will be saved")
        )
    }

    private fun alert(message: String) =
        JOptionPane.showMessageDialog(this, message, "SonarJira Issuer", JOptionPane.WARNING_MESSAGE)

    // ── Section DSL ────────────────────────────────────────────────────────────

    private fun section(title: String, content: SectionDsl.() -> Unit): JPanel {
        val inner = JPanel(GridBagLayout())
        SectionDsl(inner).content()

        val headerPanel = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints()
            gbc.gridx = 0; gbc.anchor = GridBagConstraints.WEST
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets = Insets(0, 0, 0, JBUI.scale(8))
            add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets = Insets(0, 0, 0, 0)
            add(JSeparator(), gbc)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(headerPanel, BorderLayout.NORTH)
            add(inner,       BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(24))
        }
    }

    private inner class SectionDsl(private val panel: JPanel) {
        private var row = 0

        fun row(label: String, component: JComponent, hint: String?) {
            val gbc = GridBagConstraints()
            gbc.gridx = 0; gbc.gridy = row; gbc.anchor = GridBagConstraints.NORTHWEST
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets = Insets(JBUI.scale(5), JBUI.scale(6), JBUI.scale(4), JBUI.scale(8))
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets = Insets(JBUI.scale(4), 0, JBUI.scale(4), JBUI.scale(4))
            panel.add(component, gbc)
            if (hint != null) {
                gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
                gbc.insets = Insets(JBUI.scale(5), JBUI.scale(4), JBUI.scale(4), JBUI.scale(6))
                panel.add(JBLabel(hint).apply { foreground = JBColor.GRAY }, gbc)
            }
            row++
        }

        fun component(comp: JComponent) {
            val gbc = GridBagConstraints()
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.anchor = GridBagConstraints.NORTHWEST
            gbc.insets = Insets(JBUI.scale(4), JBUI.scale(6), JBUI.scale(4), JBUI.scale(4))
            panel.add(comp, gbc)
            row++
        }
    }
}
