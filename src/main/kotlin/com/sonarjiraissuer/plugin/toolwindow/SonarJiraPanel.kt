package com.sonarjiraissuer.plugin.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sonarjiraissuer.plugin.api.SonarApiException
import com.sonarjiraissuer.plugin.api.SonarProject
import com.sonarjiraissuer.plugin.api.SonarQubeClient
import com.sonarjiraissuer.plugin.api.dto.SonarIssue
import com.sonarjiraissuer.plugin.model.IssueType
import com.sonarjiraissuer.plugin.model.JiraFormatterConfig
import com.sonarjiraissuer.plugin.service.FileExportService
import com.sonarjiraissuer.plugin.service.IssueGroupingService
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
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Main UI panel for the SonarJira Issuer tool window.
 *
 * Improvements over v1:
 *  - Token is loaded from / saved to IntelliJ PasswordSafe (OS keychain).
 *  - "Test Connection" validates the token inline and stores it on success.
 *  - "Browse…" discovers accessible projects from the server, no manual key entry needed.
 *  - Modern section headers (bold label + separator line) replace etched titled borders.
 *  - Richer error messages with HTTP-specific guidance.
 *
 * Layout (top-to-bottom, scrollable):
 *   [1] Connection    — URL · Token + Test · Project Key + Browse
 *   [2] Issue Types   — checkboxes for each SonarQube type
 *   [3] Load Issues   — button + inline status
 *   [4] Generation Settings
 *   [5] Preview
 *   [6] Export
 */
class SonarJiraPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log      = Logger.getInstance(SonarJiraPanel::class.java)
    private val settings = PluginSettings.getInstance()

    // ── Section 1: Connection ──────────────────────────────────────────────────
    private val urlField             = JBTextField(settings.serverUrl, 30)
    private val tokenField           = JPasswordField(20)
    private val projectKeyField      = JBTextField(settings.projectKey, 30)
    private val testConnectionButton = JButton("Test")
    private val browseProjectButton  = JButton("Browse…")

    /** Small label shown below the token field: "✓ Token stored securely" or error. */
    private val tokenStatusLabel = JBLabel("").apply {
        font = JBUI.Fonts.smallFont()
    }

    // ── Section 2: Issue types ─────────────────────────────────────────────────
    private val issueTypeBoxes: Map<IssueType, JCheckBox> =
        IssueType.values().associateWith { type ->
            JCheckBox(type.displayName, type.apiValue in settings.selectedIssueTypes)
        }

    // ── Section 3: Load ────────────────────────────────────────────────────────
    private val loadButton  = JButton("▶  Load Issues")
    private val statusLabel = JBLabel("Not loaded.").apply { font = JBUI.Fonts.smallFont() }
    private var loadedIssues: List<SonarIssue> = emptyList()

    // ── Section 4: Generation settings ────────────────────────────────────────
    private val batchSizeField    = JBTextField(settings.batchSize.toString(), 8)
    private val titlePrefixField  = JBTextField(settings.titlePrefix, 28)
    private val jiraTypeField     = JBTextField(settings.jiraIssueType, 15)
    private val jiraPriorityField = JBTextField(settings.jiraPriority, 15)
    private val jiraLabelsField   = JBTextField(settings.jiraLabels, 35)
    private val outputFolderField = TextFieldWithBrowseButton()

    // ── Section 5: Preview ─────────────────────────────────────────────────────
    private val previewArea = JTextArea(14, 60).apply {
        isEditable = false
        font       = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11f).toInt())
        lineWrap   = false
        tabSize    = 2
    }

    // ── Section 6: Generate ────────────────────────────────────────────────────
    private val generateButton = JButton("⬇  Generate Jira Ticket File").apply { isEnabled = false }

    // ──────────────────────────────────────────────────────────────────────────

    init {
        border = JBUI.Borders.empty(8)
        loadSavedToken()               // populate tokenField from PasswordSafe
        configureOutputFolderChooser()
        buildLayout()
        wireListeners()
    }

    // ── Startup: load token from PasswordSafe ──────────────────────────────────

    private fun loadSavedToken() {
        val saved = settings.loadSavedToken()
        if (saved.isNotBlank()) {
            tokenField.text = saved
            showTokenStored("✓ Token stored securely in IntelliJ Keychain")
        }
    }

    private fun showTokenStored(message: String = "✓ Token stored securely") {
        ApplicationManager.getApplication().invokeLater {
            tokenStatusLabel.text       = message
            tokenStatusLabel.foreground = JBColor(0x2E7D32, 0x4CAF50)
        }
    }

    private fun showTokenError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            tokenStatusLabel.text       = "✗ $message"
            tokenStatusLabel.foreground = JBColor.RED
        }
    }

    private fun clearTokenStatus() {
        tokenStatusLabel.text = ""
    }

    // ── Layout construction ────────────────────────────────────────────────────

    private fun buildLayout() {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2)

            // ── 1. Connection ────────────────────────────────────────────────
            add(section("Connection") {
                row("Server URL:", urlField, "e.g. https://sonarcloud.io")

                // Token row: password field + inline "Test" button
                val tokenInput = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(tokenField,           BorderLayout.CENTER)
                    add(testConnectionButton, BorderLayout.EAST)
                }
                // Stack field+button above the status badge
                val tokenColumn = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(tokenInput.also  { it.alignmentX = LEFT_ALIGNMENT })
                    add(tokenStatusLabel.also { it.alignmentX = LEFT_ALIGNMENT })
                }
                row("Token:", tokenColumn, null)

                // Project Key row: text field + "Browse…" button
                val projectInput = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(projectKeyField,     BorderLayout.CENTER)
                    add(browseProjectButton, BorderLayout.EAST)
                }
                row("Project Key:", projectInput, "Filled automatically by Browse…")
            })

            add(vspace())

            // ── 2. Issue Types ───────────────────────────────────────────────
            add(section("Issue Types to Retrieve") {
                val cbRow = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    issueTypeBoxes.values.forEach { cb ->
                        add(cb)
                        add(Box.createHorizontalStrut(JBUI.scale(14)))
                    }
                    add(Box.createHorizontalGlue())
                }
                component(cbRow)
            })

            add(vspace())

            // ── 3. Load ──────────────────────────────────────────────────────
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

            // ── 4. Generation Settings ───────────────────────────────────────
            add(section("Generation Settings") {
                row("Issues per ticket:", batchSizeField,    "Issues grouped into each Jira ticket")
                row("Title prefix:",      titlePrefixField,  "Prepended to each ticket summary")
                row("Jira Issue Type:",   jiraTypeField,     "e.g. Task, Story")
                row("Priority:",          jiraPriorityField, "e.g. Medium, High, Critical")
                row("Labels (CSV):",      jiraLabelsField,   "Comma-separated Jira labels")
                row("Output folder:",     outputFolderField, null)
            })

            add(vspace())

            // ── 5. Preview ───────────────────────────────────────────────────
            add(section("Preview") {
                component(JBScrollPane(previewArea).apply {
                    preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(210))
                    maximumSize   = Dimension(Int.MAX_VALUE, JBUI.scale(210))
                })
            })

            add(vspace())

            // ── 6. Export ────────────────────────────────────────────────────
            add(section("Export") {
                component(generateButton)
            })

            add(Box.createVerticalGlue())
        }

        add(JBScrollPane(body), BorderLayout.CENTER)
    }

    private fun vspace() = Box.createVerticalStrut(JBUI.scale(4))

    private fun configureOutputFolderChooser() {
        outputFolderField.text = settings.outputFolder.ifEmpty {
            project.basePath ?: System.getProperty("user.home") ?: ""
        }
        outputFolderField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Output Folder")
                .withDescription("Choose the directory where the generated ticket file will be saved")
        )
    }

    // ── Event wiring ───────────────────────────────────────────────────────────

    private fun wireListeners() {
        loadButton.addActionListener           { onLoad() }
        generateButton.addActionListener       { onGenerate() }
        testConnectionButton.addActionListener { onTestConnection() }
        browseProjectButton.addActionListener  { onBrowseProjects() }

        // Clear the "stored" badge whenever the user edits the token field
        tokenField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = clearTokenStatus()
            override fun removeUpdate(e: DocumentEvent)  = clearTokenStatus()
            override fun changedUpdate(e: DocumentEvent) = clearTokenStatus()
        })

        // Live-update preview when batch size changes
        batchSizeField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = refreshPreview()
            override fun removeUpdate(e: DocumentEvent)  = refreshPreview()
            override fun changedUpdate(e: DocumentEvent) = refreshPreview()
        })
    }

    // ── Test Connection ────────────────────────────────────────────────────────

    private fun onTestConnection() {
        val url   = urlField.text.trim()
        val token = String(tokenField.password).trim()

        if (!isValidUrl(url)) { alert("SonarQube URL must start with http:// or https://"); return }
        if (token.isEmpty())  { alert("Please enter a token first."); return }

        testConnectionButton.isEnabled = false
        tokenStatusLabel.text          = "Testing connection…"
        tokenStatusLabel.foreground    = JBColor.foreground()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Testing SonarQube Connection", false) {
                private var error: String? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text            = "Connecting to $url…"
                    try {
                        SonarQubeClient(url, token).validateConnection()
                    } catch (e: SonarApiException) {
                        error = e.userMessage()
                    } catch (e: Exception) {
                        error = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }

                override fun onSuccess() {
                    testConnectionButton.isEnabled = true
                    val err = error
                    if (err != null) {
                        showTokenError(err)
                    } else {
                        settings.saveToken(token)
                        settings.serverUrl = url
                        showTokenStored("✓ Connected — token saved securely")
                        NotificationHelper.notifyInfo(
                            project, "Connection Successful",
                            "Successfully connected to SonarQube at $url"
                        )
                    }
                }
            }
        )
    }

    // ── Browse Projects ────────────────────────────────────────────────────────

    private fun onBrowseProjects() {
        val url   = urlField.text.trim()
        val token = String(tokenField.password).trim()

        if (!isValidUrl(url)) {
            alert("Please enter a valid SonarQube URL first (http:// or https://).")
            return
        }
        if (token.isEmpty()) {
            alert("Please enter your SonarQube token first.")
            return
        }

        browseProjectButton.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Modal(project, "Loading SonarQube Projects", false) {
                private var projects: List<SonarProject> = emptyList()
                private var error: String? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text            = "Fetching accessible projects…"
                    try {
                        projects = SonarQubeClient(url, token).searchProjects()
                    } catch (e: SonarApiException) {
                        error = e.userMessage()
                    } catch (e: Exception) {
                        error = "Failed to load projects: ${e.message ?: e.javaClass.simpleName}"
                    }
                }

                override fun onSuccess() {
                    browseProjectButton.isEnabled = true
                    val err = error
                    if (err != null) {
                        NotificationHelper.notifyError(project, "Project Load Failed", err)
                        return
                    }
                    if (projects.isEmpty()) {
                        alert(
                            "No projects found.\n\n" +
                            "Verify that:\n" +
                            "• The token has 'Browse' permission on at least one project\n" +
                            "• The server URL is correct (e.g. https://sonarcloud.io)"
                        )
                        return
                    }
                    val dialog = ProjectBrowseDialog(project, projects)
                    if (dialog.showAndGet()) {
                        dialog.selectedProject?.let { proj ->
                            projectKeyField.text = proj.key
                            settings.projectKey  = proj.key
                        }
                    }
                }
            }
        )
    }

    // ── Load Issues ────────────────────────────────────────────────────────────

    private fun onLoad() {
        val url        = urlField.text.trim()
        val token      = String(tokenField.password).trim()
        val projectKey = projectKeyField.text.trim()
        val types      = selectedTypes()

        // Input validation
        if (!isValidUrl(url))     { alert("SonarQube URL must start with http:// or https://"); return }
        if (token.isEmpty())      { alert("Token is required."); return }
        if (projectKey.isEmpty()) { alert("Project Key is required.\nUse the Browse… button to discover it."); return }
        if (types.isEmpty())      { alert("Select at least one issue type."); return }

        persistConnectionSettings(url, projectKey)
        setStatus("Loading issues…", JBColor.foreground())
        loadButton.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading SonarQube Issues", true) {
                private var result: List<SonarIssue> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction        = 0.05
                    indicator.text            = "Connecting to SonarQube…"

                    val client = SonarQubeClient(url, token)
                    result = client.fetchAllIssues(
                        projectKey = projectKey,
                        issueTypes = types
                    ) { fetched, total ->
                        if (indicator.isCanceled) throw InterruptedException("Cancelled by user")
                        indicator.fraction = if (total > 0) fetched.toDouble() / total else 0.5
                        indicator.text     = "Loaded $fetched / $total issues…"
                    }
                }

                override fun onSuccess() {
                    loadedIssues             = result
                    loadButton.isEnabled     = true
                    generateButton.isEnabled = result.isNotEmpty()

                    val statusColor = if (result.isNotEmpty()) JBColor(0x2E7D32, 0x4CAF50)
                                      else JBColor.foreground()
                    setStatus(buildSummary(result), statusColor)
                    refreshPreview()

                    // Persist the token on first successful load
                    settings.saveToken(token)
                    showTokenStored()
                    log.info("Loaded ${result.size} issues from '$projectKey'")
                }

                override fun onThrowable(error: Throwable) {
                    loadButton.isEnabled = true
                    val msg = when (error) {
                        is SonarApiException    -> error.userMessage()
                        is InterruptedException -> "Load cancelled."
                        else -> "Unexpected error: ${error.message ?: error.javaClass.simpleName}"
                    }
                    setStatus("✗ $msg", JBColor.RED)
                    NotificationHelper.notifyError(project, "Load Failed", msg)
                    log.warn("Failed to load SonarQube issues", error)
                }
            }
        )
    }

    // ── Generate / Export ──────────────────────────────────────────────────────

    private fun onGenerate() {
        if (loadedIssues.isEmpty()) {
            alert("No issues loaded. Click 'Load Issues' first.")
            return
        }

        val outputFolder = outputFolderField.text.trim()
        if (outputFolder.isEmpty()) {
            alert("Output folder is required.")
            return
        }

        val batchSize = batchSizeField.text.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: run { alert("'Issues per ticket' must be a positive integer."); return }

        persistGenerationSettings(batchSize)
        generateButton.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Jira Ticket File", false) {
                private var filePath  = ""
                private var newCount  = 0
                private var skipCount = 0

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    filePath = FileExportService.resolveFilePath(outputFolder, settings.projectKey)

                    indicator.text = "Checking for already-exported issues…"
                    val alreadyExported = FileExportService.readExportedKeys(filePath)
                    val isAppend        = alreadyExported.isNotEmpty()

                    val newIssues = loadedIssues.filter { it.key !in alreadyExported }
                    skipCount     = loadedIssues.size - newIssues.size
                    newCount      = newIssues.size

                    if (newIssues.isEmpty()) return

                    indicator.text = "Grouping ${newIssues.size} new issues…"
                    val allGroups = IssueGroupingService
                        .groupByType(newIssues, batchSize)
                        .values.flatten()

                    indicator.text = "Formatting ${allGroups.size} ticket(s)…"
                    val content = JiraTextFormatter(buildFormatterConfig()).format(allGroups, isAppend)

                    indicator.text = "Writing file…"
                    FileExportService.append(content, filePath)

                    ApplicationManager.getApplication().invokeLater {
                        previewArea.text          = content
                        previewArea.caretPosition = 0
                    }
                }

                override fun onSuccess() {
                    generateButton.isEnabled = true
                    if (newCount == 0) {
                        val msg = "All ${loadedIssues.size} loaded issues are already in the file — nothing appended."
                        NotificationHelper.notifyInfo(project, "Already Up to Date", msg)
                        setStatus(msg, JBColor.foreground())
                    } else {
                        val skipMsg = if (skipCount > 0) "  ($skipCount already exported, skipped)" else ""
                        val msg     = "$newCount new issue(s) exported → $filePath$skipMsg"
                        NotificationHelper.notifyInfo(project, "Jira Tickets Exported", msg)
                        setStatus("✓ $msg", JBColor(0x2E7D32, 0x4CAF50))
                        log.info("Appended $newCount issues to $filePath")
                    }
                }

                override fun onThrowable(error: Throwable) {
                    generateButton.isEnabled = true
                    val msg = "Export failed: ${error.message ?: error.javaClass.simpleName}"
                    NotificationHelper.notifyError(project, "Export Failed", msg)
                    setStatus("✗ $msg", JBColor.RED)
                    log.warn("Export failed", error)
                }
            }
        )
    }

    // ── Preview helpers ────────────────────────────────────────────────────────

    private fun refreshPreview() {
        if (loadedIssues.isEmpty()) return
        val batchSize = batchSizeField.text.toIntOrNull()?.takeIf { it > 0 }
            ?: settings.batchSize

        val grouped   = IssueGroupingService.groupByType(loadedIssues, batchSize)
        val allGroups = grouped.values.flatten()

        val text = buildString {
            appendLine("Preview: ${allGroups.size} Jira ticket(s) will be generated")
            appendLine()
            allGroups.forEach { g ->
                val sp = StoryPointCalculator.format(g.storyPoints)
                appendLine("  [Ticket ${g.batchIndex}/${g.totalBatches}]  " +
                        "${g.issueType.padEnd(20)}  ${g.issues.size} issues   $sp SP")
            }
        }
        ApplicationManager.getApplication().invokeLater { previewArea.text = text }
    }

    // ── Utility helpers ────────────────────────────────────────────────────────

    private fun isValidUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun selectedTypes(): List<String> =
        issueTypeBoxes.entries.filter { (_, cb) -> cb.isSelected }.map { (t, _) -> t.apiValue }

    private fun buildSummary(issues: List<SonarIssue>): String {
        if (issues.isEmpty()) return "No issues found matching the selected filters."
        val byType = issues.groupBy { it.type }
            .entries.sortedBy { it.key }
            .joinToString("  |  ") { "${it.key}: ${it.value.size}" }
        return "${issues.size} issue(s) loaded  —  $byType"
    }

    private fun buildFormatterConfig() = JiraFormatterConfig(
        projectKey    = settings.projectKey,
        serverUrl     = settings.serverUrl,
        titlePrefix   = titlePrefixField.text.trim(),
        jiraIssueType = jiraTypeField.text.trim(),
        jiraPriority  = jiraPriorityField.text.trim(),
        jiraLabels    = jiraLabelsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    )

    private fun setStatus(text: String, color: Color) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text       = text
            statusLabel.foreground = color
        }
    }

    private fun alert(message: String) {
        JOptionPane.showMessageDialog(this, message, "SonarJira Issuer", JOptionPane.WARNING_MESSAGE)
    }

    private fun persistConnectionSettings(url: String, projectKey: String) {
        settings.serverUrl          = url
        settings.projectKey         = projectKey
        settings.selectedIssueTypes = selectedTypes()
    }

    private fun persistGenerationSettings(batchSize: Int) {
        settings.batchSize     = batchSize
        settings.titlePrefix   = titlePrefixField.text.trim()
        settings.jiraIssueType = jiraTypeField.text.trim()
        settings.jiraPriority  = jiraPriorityField.text.trim()
        settings.jiraLabels    = jiraLabelsField.text.trim()
        settings.outputFolder  = outputFolderField.text.trim()
    }

    // ── Section DSL ────────────────────────────────────────────────────────────

    /**
     * Creates a section panel with a modern header (bold title + separator line)
     * instead of a legacy etched titled border.
     *
     * Example output:
     *   Connection ──────────────────────────────────────
     *   [content rows]
     */
    private fun section(title: String, content: SectionDsl.() -> Unit): JPanel {
        val inner = JPanel(GridBagLayout())
        SectionDsl(inner).content()

        // Header: "Title ─────────────────────────"
        val headerPanel = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints()
            gbc.gridx   = 0
            gbc.anchor  = GridBagConstraints.WEST
            gbc.fill    = GridBagConstraints.NONE
            gbc.weightx = 0.0
            gbc.insets  = Insets(0, 0, 0, JBUI.scale(8))
            add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, gbc)

            gbc.gridx   = 1
            gbc.fill    = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            gbc.insets  = Insets(0, 0, 0, 0)
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

            // Label — NORTHWEST so it aligns to the top for taller components (e.g. token column)
            gbc.gridx   = 0; gbc.gridy = row
            gbc.anchor  = GridBagConstraints.NORTHWEST
            gbc.fill    = GridBagConstraints.NONE
            gbc.weightx = 0.0
            gbc.insets  = Insets(JBUI.scale(5), JBUI.scale(6), JBUI.scale(4), JBUI.scale(8))
            panel.add(JBLabel(label), gbc)

            // Field / component
            gbc.gridx   = 1; gbc.gridy = row
            gbc.anchor  = GridBagConstraints.NORTHWEST
            gbc.fill    = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            gbc.insets  = Insets(JBUI.scale(4), 0, JBUI.scale(4), JBUI.scale(4))
            panel.add(component, gbc)

            // Optional hint
            if (hint != null) {
                gbc.gridx   = 2; gbc.gridy = row
                gbc.anchor  = GridBagConstraints.NORTHWEST
                gbc.fill    = GridBagConstraints.NONE
                gbc.weightx = 0.0
                gbc.insets  = Insets(JBUI.scale(5), JBUI.scale(4), JBUI.scale(4), JBUI.scale(6))
                panel.add(JBLabel(hint).apply { foreground = JBColor.GRAY }, gbc)
            }
            row++
        }

        fun component(comp: JComponent) {
            val gbc = GridBagConstraints()
            gbc.gridx     = 0; gbc.gridy = row
            gbc.gridwidth = 3
            gbc.fill      = GridBagConstraints.HORIZONTAL
            gbc.weightx   = 1.0
            gbc.insets    = Insets(JBUI.scale(4), JBUI.scale(6), JBUI.scale(4), JBUI.scale(6))
            panel.add(comp, gbc)
            row++
        }
    }
}
