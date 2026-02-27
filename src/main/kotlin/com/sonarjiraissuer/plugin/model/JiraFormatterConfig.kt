package com.sonarjiraissuer.plugin.model

/**
 * Immutable configuration snapshot passed to [com.sonarjiraissuer.plugin.service.JiraTextFormatter].
 * Decoupled from [com.sonarjiraissuer.plugin.settings.PluginSettings] so the formatter
 * can be unit-tested without IntelliJ platform dependencies.
 */
data class JiraFormatterConfig(
    val projectKey: String,
    val serverUrl: String,
    val titlePrefix: String,
    val jiraIssueType: String,
    val jiraPriority: String,
    val jiraLabels: List<String>
)
