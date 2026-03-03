package com.sonarjiraissuer.plugin.service

import com.sonarjiraissuer.plugin.api.SonarQubeClient
import com.sonarjiraissuer.plugin.model.IssueGroup
import com.sonarjiraissuer.plugin.model.JiraFormatterConfig
import com.sonarjiraissuer.plugin.util.EffortParser

/**
 * Builds Jira ticket descriptions in the correct format for each deployment type:
 *
 * - **Jira Cloud** (API v3, [isCloud] = true):  Atlassian Document Format (ADF) as `Map<String, Any>`.
 *   Renders as rich content — headings, tables with clickable SonarQube links, bullet list.
 *
 * - **Jira Server / Data Center** (API v2, [isCloud] = false):  Jira wiki markup as `String`.
 *   Renders as formatted content — headings, table with pipe syntax, bold text.
 *
 * The [isCloud] flag mirrors [com.sonarjiraissuer.plugin.api.JiraClient.isCloud].
 */
object JiraDescriptionBuilder {

    fun build(group: IssueGroup, config: JiraFormatterConfig, isCloud: Boolean): Any =
        if (isCloud) buildAdf(group, config) else buildWiki(group, config)

    // ── ADF (Atlassian Document Format) — Jira Cloud ──────────────────────────

    private fun buildAdf(group: IssueGroup, config: JiraFormatterConfig): Map<String, Any> {
        val effortStr = EffortParser.formatMinutes(group.totalEffortMinutes)
        val spStr     = StoryPointCalculator.format(group.storyPoints)
        val typeLabel = group.issueType.replace("_", " ")

        val content = mutableListOf<Map<String, Any>>()

        // Context
        content += adfHeading(2, "Context")
        content += adfParagraph(
            adfText("This ticket covers "),
            adfText("${group.issues.size}", bold = true),
            adfText(" $typeLabel issue(s) identified by SonarQube — batch ${group.batchIndex}/${group.totalBatches}.")
        )

        // Issues table
        content += adfHeading(2, "Issues to Fix")
        content += buildAdfTable(group, config)

        // Summary
        content += adfHeading(2, "Summary")
        content += adfParagraph(
            adfText("Total Remediation Effort: "),
            adfText(effortStr, bold = true),
            adfText("     •     Story Points: "),
            adfText("$spStr SP", bold = true),
            adfText("  (1 SP = 8h work, rounded up to nearest 0.5)")
        )

        // Definition of Done
        content += adfHeading(2, "Definition of Done")
        content += adfBulletList(
            "All listed SonarQube issues resolved (status: FIXED or CLOSED)",
            "SonarQube quality gate passes for all affected files",
            "Code reviewed, approved, and merged to main branch",
            "No new issues of this type introduced"
        )

        return mapOf("version" to 1, "type" to "doc", "content" to content)
    }

    private fun buildAdfTable(group: IssueGroup, config: JiraFormatterConfig): Map<String, Any> {
        val headers   = listOf("#", "Rule", "Severity", "File", "Line", "Message", "Effort", "SP")
        val headerRow = adfTableRow(headers.map { col ->
            adfTableHeader(adfParagraph(adfText(col, bold = true)))
        })

        val dataRows = group.issues.mapIndexed { idx, issue ->
            val effortMin = EffortParser.parseToMinutes(issue.effectiveEffort())
            val file      = issue.filePath().let { f -> if (f.length > 50) "...${f.takeLast(47)}" else f }
            val link      = SonarQubeClient.buildIssueLink(config.serverUrl, config.projectKey, issue.key)
            val sp        = StoryPointCalculator.format(StoryPointCalculator.calculate(effortMin))
            val effort    = EffortParser.formatMinutes(effortMin).ifEmpty { "-" }
            adfTableRow(listOf(
                adfTableCell(adfParagraph(adfLinked("${idx + 1}", link))),
                adfTableCell(adfParagraph(adfText(issue.rule))),
                adfTableCell(adfParagraph(adfText(issue.severity ?: "-"))),
                adfTableCell(adfParagraph(adfText(file))),
                adfTableCell(adfParagraph(adfText(issue.lineDisplay()))),
                adfTableCell(adfParagraph(adfText(issue.message.take(120)))),
                adfTableCell(adfParagraph(adfText(effort))),
                adfTableCell(adfParagraph(adfText(sp)))
            ))
        }

        return mapOf(
            "type"    to "table",
            "attrs"   to mapOf("isNumberColumnEnabled" to false, "layout" to "default"),
            "content" to listOf(headerRow) + dataRows
        )
    }

    // ── ADF node helpers ──────────────────────────────────────────────────────

    private fun adfHeading(level: Int, label: String): Map<String, Any> = mapOf(
        "type"    to "heading",
        "attrs"   to mapOf("level" to level),
        "content" to listOf(adfText(label))
    )

    private fun adfParagraph(vararg nodes: Map<String, Any>): Map<String, Any> = mapOf(
        "type"    to "paragraph",
        "content" to nodes.toList()
    )

    private fun adfParagraph(single: Map<String, Any>): Map<String, Any> = mapOf(
        "type"    to "paragraph",
        "content" to listOf(single)
    )

    private fun adfText(value: String, bold: Boolean = false): Map<String, Any> {
        val node = mutableMapOf<String, Any>("type" to "text", "text" to value)
        if (bold) node["marks"] = listOf(mapOf("type" to "strong"))
        return node
    }

    private fun adfLinked(label: String, href: String): Map<String, Any> = mapOf(
        "type"  to "text",
        "text"  to label,
        "marks" to listOf(mapOf("type" to "link", "attrs" to mapOf("href" to href)))
    )

    private fun adfTableRow(cells: List<Map<String, Any>>): Map<String, Any> = mapOf(
        "type"    to "tableRow",
        "content" to cells
    )

    private fun adfTableHeader(content: Map<String, Any>): Map<String, Any> = mapOf(
        "type"    to "tableHeader",
        "attrs"   to emptyMap<String, Any>(),
        "content" to listOf(content)
    )

    private fun adfTableCell(content: Map<String, Any>): Map<String, Any> = mapOf(
        "type"    to "tableCell",
        "attrs"   to emptyMap<String, Any>(),
        "content" to listOf(content)
    )

    private fun adfBulletList(vararg items: String): Map<String, Any> = mapOf(
        "type"    to "bulletList",
        "content" to items.map { item ->
            mapOf(
                "type"    to "listItem",
                "content" to listOf(adfParagraph(adfText(item)))
            )
        }
    )

    // ── Jira Wiki Markup — Jira Server / Data Center ──────────────────────────

    private fun buildWiki(group: IssueGroup, config: JiraFormatterConfig): String {
        val effortStr = EffortParser.formatMinutes(group.totalEffortMinutes)
        val spStr     = StoryPointCalculator.format(group.storyPoints)
        val typeLabel = group.issueType.replace("_", " ")

        return buildString {
            appendLine("h2. Context")
            appendLine()
            appendLine("This ticket covers *${group.issues.size}* $typeLabel issue(s) identified by SonarQube — batch ${group.batchIndex}/${group.totalBatches}.")
            appendLine()

            appendLine("h2. Issues to Fix")
            appendLine()
            appendLine("|| # || Rule || Severity || File || Line || Message || Effort || SP ||")
            group.issues.forEachIndexed { idx, issue ->
                val effortMin = EffortParser.parseToMinutes(issue.effectiveEffort())
                val file      = issue.filePath().let { f -> if (f.length > 50) "...${f.takeLast(47)}" else f }
                val link      = SonarQubeClient.buildIssueLink(config.serverUrl, config.projectKey, issue.key)
                val sp        = StoryPointCalculator.format(StoryPointCalculator.calculate(effortMin))
                val effort    = EffortParser.formatMinutes(effortMin).ifEmpty { "-" }
                appendLine("| [${idx + 1}|$link] | ${issue.rule} | ${issue.severity ?: "-"} | $file | ${issue.lineDisplay()} | ${issue.message.take(120)} | $effort | $sp |")
            }
            appendLine()

            appendLine("h2. Summary")
            appendLine()
            appendLine("Total Remediation Effort: *$effortStr*     •     Story Points: *$spStr SP*  (1 SP = 8h work, rounded up to nearest 0.5)")
            appendLine()

            appendLine("h2. Definition of Done")
            appendLine()
            appendLine("* All listed SonarQube issues resolved (status: FIXED or CLOSED)")
            appendLine("* SonarQube quality gate passes for all affected files")
            appendLine("* Code reviewed, approved, and merged to main branch")
            appendLine("* No new issues of this type introduced")
        }
    }
}
