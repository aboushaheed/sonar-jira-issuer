package com.sonarjiraissuer.plugin.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sonarjiraissuer.plugin.api.JiraApiException
import com.sonarjiraissuer.plugin.api.JiraClient
import com.sonarjiraissuer.plugin.api.SonarApiException
import com.sonarjiraissuer.plugin.api.SonarQubeClient
import com.sonarjiraissuer.plugin.settings.PluginSettings
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSeparator

/**
 * Minimal dialog for configuring connection credentials.
 * Opened via the **⚙ Configure** button at the top of the main panel.
 *
 * Handles only URLs + tokens — project selection stays in the main panel.
 */
class ConnectionSetupDialog(private val ideProject: Project) : DialogWrapper(ideProject) {

    private val settings = PluginSettings.getInstance()

    // ── SonarQube fields ──────────────────────────────────────────────────────
    private val sonarUrlField    = JBTextField(settings.serverUrl, 36)
    private val sonarTokenField  = JPasswordField(20)
    private val sonarTestBtn     = JButton("Test")
    private val sonarStatusLabel = statusLabel()

    // ── Jira fields ───────────────────────────────────────────────────────────
    private val jiraUrlField    = JBTextField(settings.jiraServerUrl, 36)
    private val jiraEmailField  = JBTextField(settings.jiraEmail, 36)
    private val jiraTokenField  = JPasswordField(20)
    private val jiraTestBtn     = JButton("Test")
    private val jiraStatusLabel = statusLabel()

    init {
        title = "Configure Connections"
        setOKButtonText("Save & Close")
        init()
        // Pre-fill saved tokens
        settings.loadSavedToken().takeIf { it.isNotBlank() }?.let { sonarTokenField.text = it }
        settings.loadJiraToken().takeIf  { it.isNotBlank() }?.let { jiraTokenField.text  = it }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12, 4, 12)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(300))
        }

        root.add(sectionHeader("SonarQube"))
        root.add(Box.createVerticalStrut(JBUI.scale(8)))
        root.add(formGrid {
            row("Server URL:", sonarUrlField,
                "e.g. https://sonarcloud.io  or  http://sonar.company.com")
            row("Token:",      sonarTokenField,
                "Settings → Security → Tokens")
            testRow(sonarTestBtn, sonarStatusLabel)
        })

        root.add(Box.createVerticalStrut(JBUI.scale(14)))

        root.add(sectionHeader("Jira"))
        root.add(Box.createVerticalStrut(JBUI.scale(8)))
        root.add(formGrid {
            row("Server URL:", jiraUrlField,
                "Cloud: https://company.atlassian.net   •   Server: https://jira.company.com")
            row("Email:",      jiraEmailField,
                "Cloud only — leave blank for Jira Server / Data Center")
            row("Token:",      jiraTokenField,
                "Cloud: API token   •   Server: Personal Access Token")
            testRow(jiraTestBtn, jiraStatusLabel)
        })

        sonarTestBtn.addActionListener { testSonar() }
        jiraTestBtn.addActionListener  { testJira()  }

        return root
    }

    // ── Save on OK ────────────────────────────────────────────────────────────

    override fun doOKAction() {
        settings.serverUrl     = sonarUrlField.text.trim()
        settings.jiraServerUrl = jiraUrlField.text.trim()
        settings.jiraEmail     = jiraEmailField.text.trim()
        String(sonarTokenField.password).trim().takeIf { it.isNotBlank() }?.let { settings.saveToken(it) }
        String(jiraTokenField.password).trim().takeIf  { it.isNotBlank() }?.let { settings.saveJiraToken(it) }
        super.doOKAction()
    }

    // ── Connection tests ──────────────────────────────────────────────────────

    private fun testSonar() {
        val url   = sonarUrlField.text.trim()
        val token = String(sonarTokenField.password).trim()
        if (!isValidUrl(url)) { setStatus(sonarStatusLabel, "✗ URL must start with http:// or https://", JBColor.RED); return }
        if (token.isBlank())  { setStatus(sonarStatusLabel, "✗ Token is required.", JBColor.RED); return }
        sonarTestBtn.isEnabled = false
        setStatus(sonarStatusLabel, "Testing…", JBColor.foreground())
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                SonarQubeClient(url, token).validateConnection()
                // Save on EDT so PasswordSafe can show its unlock dialog if needed
                ui {
                    settings.saveToken(token)
                    settings.serverUrl = url
                    sonarTestBtn.isEnabled = true
                    setStatus(sonarStatusLabel, "✓ Connected — token saved.", ok())
                }
            } catch (t: Throwable) {
                val msg = when (t) {
                    is SonarApiException -> t.userMessage()
                    else                 -> "Failed: ${t.message ?: t.javaClass.simpleName}"
                }
                ui { sonarTestBtn.isEnabled = true; setStatus(sonarStatusLabel, "✗ $msg", JBColor.RED) }
            }
        }
    }

    private fun testJira() {
        val url   = jiraUrlField.text.trim()
        val email = jiraEmailField.text.trim()
        val token = String(jiraTokenField.password).trim()
        if (!isValidUrl(url)) { setStatus(jiraStatusLabel, "✗ URL must start with http:// or https://", JBColor.RED); return }
        if (token.isBlank())  { setStatus(jiraStatusLabel, "✗ Token is required.", JBColor.RED); return }
        jiraTestBtn.isEnabled = false
        setStatus(jiraStatusLabel, "Testing…", JBColor.foreground())
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val user = JiraClient(url, token, email).testConnection()
                // Save on EDT so PasswordSafe can show its unlock dialog if needed
                ui {
                    settings.saveJiraToken(token)
                    settings.jiraServerUrl = url
                    settings.jiraEmail     = email
                    jiraTestBtn.isEnabled  = true
                    setStatus(jiraStatusLabel, "✓ Connected as ${user.displayName}.", ok())
                }
            } catch (t: Throwable) {
                val msg = when (t) {
                    is JiraApiException -> t.userMessage()
                    else                -> "Failed: ${t.message ?: t.javaClass.simpleName}"
                }
                ui { jiraTestBtn.isEnabled = true; setStatus(jiraStatusLabel, "✗ $msg", JBColor.RED) }
            }
        }
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private fun sectionHeader(title: String): JPanel =
        JPanel(GridBagLayout()).also { panel ->
            panel.alignmentX = 0f
            val gbc = GridBagConstraints()
            gbc.gridx = 0; gbc.anchor = GridBagConstraints.WEST
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets = Insets(0, 0, 0, JBUI.scale(8))
            panel.add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets = Insets(0, 0, 0, 0)
            panel.add(JSeparator(), gbc)
        }

    private fun formGrid(block: FormDsl.() -> Unit): JPanel {
        val p = JPanel(GridBagLayout()).apply { alignmentX = 0f }
        FormDsl(p).block()
        return p
    }

    private class FormDsl(private val p: JPanel) {
        private var row = 0

        fun row(label: String, comp: JComponent, hint: String?) {
            val gbc = GridBagConstraints().also { it.gridy = row; it.anchor = GridBagConstraints.NORTHWEST }
            gbc.gridx = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets = Insets(JBUI.scale(4), 0, JBUI.scale(2), JBUI.scale(8))
            p.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets = Insets(JBUI.scale(3), 0, JBUI.scale(2), 0)
            p.add(comp, gbc)
            if (hint != null) {
                gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
                gbc.insets = Insets(JBUI.scale(4), JBUI.scale(6), JBUI.scale(2), 0)
                p.add(JBLabel(hint).apply { foreground = JBColor.GRAY }, gbc)
            }
            row++
        }

        fun testRow(btn: JButton, status: JBLabel) {
            val gbc = GridBagConstraints().also {
                it.gridy = row; it.gridx = 1; it.anchor = GridBagConstraints.NORTHWEST
                it.fill = GridBagConstraints.NONE; it.weightx = 0.0
                it.insets = Insets(JBUI.scale(2), 0, JBUI.scale(4), 0)
            }
            val row2 = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(btn)
                add(Box.createHorizontalStrut(JBUI.scale(10)))
                add(status)
            }
            p.add(row2, gbc)
            row++
        }
    }

    private fun statusLabel() = JBLabel("").apply { font = JBUI.Fonts.smallFont() }
    private fun setStatus(l: JBLabel, t: String, c: java.awt.Color) { l.text = t; l.foreground = c }
    private fun ok()  = JBColor(0x2E7D32, 0x4CAF50)
    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)
    private fun isValidUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")
}
