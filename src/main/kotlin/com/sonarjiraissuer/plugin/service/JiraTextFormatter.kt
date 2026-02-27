package com.sonarjiraissuer.plugin.service

import com.sonarjiraissuer.plugin.api.SonarQubeClient
import com.sonarjiraissuer.plugin.model.IssueGroup
import com.sonarjiraissuer.plugin.model.JiraFormatterConfig
import com.sonarjiraissuer.plugin.util.EffortParser
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Formats [IssueGroup] objects into plain-text ASCII art tickets, ready to copy-paste
 * into any system (Jira description field, email, Teams message, Confluence, etc.)
 * without any wiki markup.
 *
 * ## Output structure (per ticket block)
 * ```
 * ================================================================================
 * TICKET 1/5  |  CODE SMELL
 * ================================================================================
 *
 * SUMMARY
 *   [PREFIX] CODE SMELL — batch 1/5 — 10 issues to fix
 *
 * JIRA FIELDS
 *   Issue Type   : Task
 *   Priority     : Medium
 *   Labels       : quality, sonar, technical-debt
 *   Story Points : 1.0
 *   Assignee     : (unassigned)
 *
 * DESCRIPTION
 *   This ticket covers 10 CODE SMELL issue(s) identified by SonarQube (batch 1/5).
 *
 *   +---+----------+----------+-----+------+------------------+--------+-----+
 *   | # | Rule     | Severity | ... | Line | Message          | Effort |  SP |
 *   +---+----------+----------+-----+------+------------------+--------+-----+
 *   | 1 | java:S.. | MAJOR    | ... |   42 | Define a const…  |    30m | 0.5 |
 *   +---+----------+----------+-----+------+------------------+--------+-----+
 *
 *   Total Remediation Effort : 30m
 *   Total Story Points       : 0.5 SP  (1 SP = 8h work, rounded up to 0.5)
 *
 * LINKS
 *   [  1] https://sonarqube.company.com/project/issues?id=...&open=...
 *
 * DEFINITION OF DONE
 *   [ ] All listed SonarQube issues resolved (status: FIXED or CLOSED)
 *   [ ] SonarQube quality gate passes for all affected files
 *   [ ] Code reviewed, approved, and merged to main branch
 *   [ ] No new issues of this type introduced
 * ```
 */
class JiraTextFormatter(private val config: JiraFormatterConfig) {

    private val DIVIDER = "=".repeat(80)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Format [groups] into a full document string ready to append to (or create) the output file.
     *
     * @param isAppend  `true` when content is being appended to an existing file.
     *                  Produces a compact "--- NEW EXPORT ---" separator instead of a full header.
     */
    fun format(groups: List<IssueGroup>, isAppend: Boolean = false): String {
        if (groups.isEmpty()) return ""

        return buildString {
            if (isAppend) {
                appendLine()
                appendLine(DIVIDER)
                appendLine("NEW EXPORT — ${timestamp()}  |  ${groups.sumOf { it.issues.size }} new issues")
                appendLine(DIVIDER)
                appendLine()
            } else {
                appendLine(fileHeader(groups))
            }

            groups.forEach { group ->
                appendLine(ticketBlock(group))
                appendLine()
            }
        }
    }

    // ── File-level header (first run only) ────────────────────────────────────

    private fun fileHeader(groups: List<IssueGroup>): String = buildString {
        val totalIssues = groups.sumOf { it.issues.size }
        val totalSP     = StoryPointCalculator.format(groups.sumOf { it.storyPoints })

        appendLine(DIVIDER)
        appendLine("SONARQUBE → JIRA EXPORT")
        appendLine("Generated : ${timestamp()}")
        appendLine("Project   : ${config.projectKey}")
        appendLine("Server    : ${config.serverUrl}")
        appendLine("Tickets   : ${groups.size}  |  Issues: $totalIssues  |  Total SP: $totalSP")
        appendLine(DIVIDER)
    }

    // ── Per-ticket block ──────────────────────────────────────────────────────

    private fun ticketBlock(group: IssueGroup): String {
        val effortStr = EffortParser.formatMinutes(group.totalEffortMinutes)
        val spStr     = StoryPointCalculator.format(group.storyPoints)
        val typeLabel = group.issueType.replace("_", " ")

        return buildString {
            appendLine(DIVIDER)
            appendLine("TICKET ${group.batchIndex}/${group.totalBatches}  |  ${group.issueType}")
            appendLine(DIVIDER)
            appendLine()

            appendLine("SUMMARY")
            appendLine("  ${buildSummary(group)}")
            appendLine()

            appendLine("JIRA FIELDS")
            appendLine("  Issue Type   : ${config.jiraIssueType}")
            appendLine("  Priority     : ${config.jiraPriority}")
            appendLine("  Labels       : ${config.jiraLabels.joinToString(", ")}")
            appendLine("  Story Points : $spStr")
            appendLine("  Assignee     : (unassigned)")
            appendLine()

            appendLine("DESCRIPTION")
            appendLine("  This ticket covers ${group.issues.size} $typeLabel issue(s) " +
                       "identified by SonarQube (batch ${group.batchIndex}/${group.totalBatches}).")
            appendLine()
            appendLine(issueTable(group))
            appendLine()
            appendLine("  Total Remediation Effort : $effortStr")
            appendLine("  Total Story Points       : $spStr SP  (1 SP = 8h work, rounded up to 0.5)")
            appendLine()

            appendLine("DEFINITION OF DONE")
            appendLine("  [ ] All listed SonarQube issues resolved (status: FIXED or CLOSED)")
            appendLine("  [ ] SonarQube quality gate passes for all affected files")
            appendLine("  [ ] Code reviewed, approved, and merged to main branch")
            appendLine("  [ ] No new issues of this type introduced")
        }
    }

    private fun buildSummary(group: IssueGroup): String {
        val typeLabel = group.issueType.replace("_", " ")
        return "${config.titlePrefix} $typeLabel — " +
               "batch ${group.batchIndex}/${group.totalBatches} — " +
               "${group.issues.size} issues to fix"
    }

    // ── ASCII art issue table ─────────────────────────────────────────────────

    private fun issueTable(group: IssueGroup): String {
        val headers    = listOf("#", "Rule", "Severity", "File", "Line", "Message", "Effort", "SP", "Link")
        // RIGHT-aligned column indices
        val rightAlign = setOf(0, 4, 6, 7)

        data class Row(val cells: List<String>)

        val rows = group.issues.mapIndexed { idx, issue ->
            val effortMin = EffortParser.parseToMinutes(issue.effectiveEffort())
            val file      = issue.filePath().let { f -> if (f.length > 50) "...${f.takeLast(47)}" else f }
            val link      = SonarQubeClient.buildIssueLink(config.serverUrl, config.projectKey, issue.key)
            Row(listOf(
                (idx + 1).toString(),
                issue.rule,
                issue.severity ?: "-",
                file,
                issue.lineDisplay(),
                issue.message.take(65),
                EffortParser.formatMinutes(effortMin).ifEmpty { "-" },
                StoryPointCalculator.format(StoryPointCalculator.calculate(effortMin)),
                link
            ))
        }

        // Column widths = max of header and all cell values
        val widths = headers.indices.map { col ->
            maxOf(
                headers[col].length,
                rows.maxOfOrNull { it.cells[col].length } ?: 0
            )
        }

        fun separator(): String =
            "+" + widths.joinToString("+") { "-".repeat(it + 2) } + "+"

        fun dataRow(cells: List<String>): String =
            "|" + cells.mapIndexed { i, c ->
                if (i in rightAlign) " ${c.padStart(widths[i])} "
                else                 " ${c.padEnd(widths[i])} "
            }.joinToString("|") + "|"

        return buildString {
            appendLine("  ${separator()}")
            appendLine("  ${dataRow(headers)}")
            appendLine("  ${separator()}")
            rows.forEach { row -> appendLine("  ${dataRow(row.cells)}") }
            append("  ${separator()}")
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun timestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
