package com.sonarjiraissuer.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarjiraissuer.plugin.model.IssueGroup
import com.sonarjiraissuer.plugin.service.StoryPointCalculator
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
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Let the user pick which ticket groups to push to Jira.
 *
 * @param allGroups      Every ticket group (after batching).
 * @param jiraKeyByGroup Map of group → existing Jira ticket key.
 *                       Groups present in this map are shown as "already created"
 *                       (informational, not selectable).
 *
 * After `showAndGet() == true`, read [selectedGroups].
 */
class SelectTicketsDialog(
    ideProject: Project,
    private val allGroups: List<IssueGroup>,
    private val jiraKeyByGroup: Map<IssueGroup, String>
) : DialogWrapper(ideProject) {

    private val newGroups    = allGroups.filter { it !in jiraKeyByGroup }
    private val doneGroups   = allGroups.filter { it in  jiraKeyByGroup }

    /** Groups the user chose to create (non-empty after OK). */
    var selectedGroups: List<IssueGroup> = emptyList()
        private set

    private val checkboxes: Map<IssueGroup, JCheckBox> = newGroups.associateWith { group ->
        JCheckBox(rowLabel(group), true).apply {
            font           = monoFont()
            addActionListener { updateOkButton() }
        }
    }

    init {
        title = "Select Tickets to Create in Jira"
        setOKButtonText(if (newGroups.isEmpty()) "Nothing to Create" else "Create ${newGroups.size} Ticket(s)")
        isOKActionEnabled = newGroups.isNotEmpty()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        // ── Summary header ────────────────────────────────────────────────────
        val summary = buildString {
            if (newGroups.isNotEmpty())  append("${newGroups.size} new ticket(s) to create")
            if (doneGroups.isNotEmpty()) {
                if (newGroups.isNotEmpty()) append("   •   ")
                append("${doneGroups.size} already in Jira")
            }
        }
        root.add(JBLabel(summary).apply {
            font       = JBUI.Fonts.label().asBold()
            alignmentX = 0f
        })
        root.add(Box.createVerticalStrut(JBUI.scale(6)))

        // ── New groups (selectable) ───────────────────────────────────────────
        if (newGroups.isNotEmpty()) {
            root.add(sectionHeader("New — select which ones to create"))

            // Toolbar: Select All / Deselect All
            if (newGroups.size > 1) {
                val bar = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(JButton("Select All").apply {
                        addActionListener { checkboxes.values.forEach { it.isSelected = true }; updateOkButton() }
                    })
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(JButton("Deselect All").apply {
                        addActionListener { checkboxes.values.forEach { it.isSelected = false }; updateOkButton() }
                    })
                    add(Box.createHorizontalGlue())
                    alignmentX = 0f
                }
                root.add(bar)
                root.add(Box.createVerticalStrut(JBUI.scale(4)))
            }

            val newPanel = JPanel(GridBagLayout())
            newGroups.forEachIndexed { i, group ->
                val cb  = checkboxes.getValue(group)
                val gbc = rowGbc(i)
                newPanel.add(cb, gbc)
            }
            root.add(JBScrollPane(newPanel).apply {
                preferredSize = Dimension(
                    JBUI.scale(560),
                    JBUI.scale(minOf(newGroups.size * 30 + 8, 280))
                )
                border = JBUI.Borders.empty()
                alignmentX = 0f
            })
        }

        // ── Already-created groups (informational) ────────────────────────────
        if (doneGroups.isNotEmpty()) {
            root.add(Box.createVerticalStrut(JBUI.scale(8)))
            root.add(sectionHeader("Already in Jira — will be skipped"))

            val donePanel = JPanel(GridBagLayout())
            doneGroups.forEachIndexed { i, group ->
                val jiraKey = jiraKeyByGroup.getValue(group)
                val label   = JBLabel("✓  ${rowLabel(group)}    → $jiraKey").apply {
                    font       = monoFont()
                    foreground = JBColor(0x2E7D32, 0x4CAF50)
                }
                donePanel.add(label, rowGbc(i).also { it.insets = Insets(JBUI.scale(2), JBUI.scale(22), JBUI.scale(2), JBUI.scale(4)) })
            }
            root.add(JBScrollPane(donePanel).apply {
                preferredSize = Dimension(
                    JBUI.scale(560),
                    JBUI.scale(minOf(doneGroups.size * 28 + 8, 160))
                )
                border = JBUI.Borders.empty()
                alignmentX = 0f
            })
        }

        return root.apply { border = JBUI.Borders.empty(10, 14, 6, 14) }
    }

    override fun doOKAction() {
        selectedGroups = newGroups.filter { checkboxes.getValue(it).isSelected }
        super.doOKAction()
    }

    private fun updateOkButton() {
        val count = checkboxes.values.count { it.isSelected }
        isOKActionEnabled = count > 0
        okAction.putValue(
            javax.swing.Action.NAME,
            if (count > 0) "Create $count Ticket(s)" else "Select at least one"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun rowLabel(group: IssueGroup): String {
        val type = group.issueType.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
        val sp   = StoryPointCalculator.format(group.storyPoints)
        return "%-26s  Batch %d/%d   %2d issue(s)   %s SP".format(
            type, group.batchIndex, group.totalBatches, group.issues.size, sp
        )
    }

    private fun monoFont() = Font("Monospaced", Font.PLAIN, JBUI.scaleFontSize(11.5f).toInt())

    private fun rowGbc(row: Int) = GridBagConstraints().apply {
        gridx   = 0; gridy   = row
        anchor  = GridBagConstraints.WEST
        fill    = GridBagConstraints.HORIZONTAL; weightx = 1.0
        insets  = Insets(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
    }

    private fun sectionHeader(text: String): JPanel =
        JPanel(GridBagLayout()).also { panel ->
            val gbc = GridBagConstraints()
            gbc.gridx = 0; gbc.anchor = GridBagConstraints.WEST
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.insets = Insets(0, 0, 0, JBUI.scale(8))
            panel.add(JBLabel(text).apply {
                font       = JBUI.Fonts.smallFont().asBold()
                foreground = JBColor.GRAY
            }, gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.insets = Insets(0, 0, 0, 0)
            panel.add(JSeparator(), gbc)
            panel.alignmentX = 0f
        }
}
