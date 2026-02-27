package com.sonarjiraissuer.plugin.api.dto

import com.google.gson.annotations.SerializedName

/**
 * Represents a single issue returned by the SonarQube `/api/issues/search` endpoint.
 *
 * SonarQube returns effort as a human-readable string, e.g. "5min", "1h", "2h 30min", "1d".
 * Both [effort] and [debt] carry the same value; [effectiveEffort] returns the non-null one.
 */
data class SonarIssue(
    val key: String = "",
    val type: String = "",
    val severity: String? = null,
    val message: String = "",
    val component: String = "",
    val rule: String = "",
    val effort: String? = null,
    val debt: String? = null,
    val status: String = "",
    val project: String = "",
    @SerializedName("textRange") val textRange: TextRange? = null
) {
    data class TextRange(
        val startLine: Int? = null,
        val endLine: Int? = null,
        val startOffset: Int? = null,
        val endOffset: Int? = null
    )

    /** Returns the effective effort string; [effort] takes precedence over [debt]. */
    fun effectiveEffort(): String? =
        effort?.takeIf { it.isNotBlank() } ?: debt?.takeIf { it.isNotBlank() }

    /**
     * Extracts the file path relative to the project root.
     * SonarQube component format: "projectKey:src/path/File.java"
     */
    fun filePath(): String {
        val colonIndex = component.lastIndexOf(':')
        return if (colonIndex >= 0) component.substring(colonIndex + 1) else component
    }

    /** Returns the starting line number as a display string, or "-" if unavailable. */
    fun lineDisplay(): String = textRange?.startLine?.toString() ?: "-"
}
