package com.sonarjiraissuer.plugin.service

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException

/**
 * Manages the Jira ticket output file.
 *
 * File naming: `jira-tickets_{safeProjectKey}.txt`  (fixed per project — no timestamp).
 * Re-runs append new ticket blocks; issues already present are skipped automatically.
 *
 * Deduplication strategy:
 *   Every Sonar issue link in the file contains `open=<issueKey>`.
 *   We parse those URLs to build the set of already-exported keys, then the caller
 *   filters `loadedIssues` against that set before formatting.
 */
object FileExportService {

    private val log = Logger.getInstance(FileExportService::class.java)

    // Matches the issue key after "open=" in every Sonar issue URL we write
    private val ISSUE_KEY_REGEX = Regex("""open=([A-Za-z0-9_\-]+)""")

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Returns the canonical output file path for [projectKey] inside [outputFolder].
     * The name is stable across runs so appending works correctly.
     */
    fun resolveFilePath(outputFolder: String, projectKey: String): String {
        val safeKey = sanitize(projectKey)
        return File(outputFolder, "jira-tickets_$safeKey.txt").absolutePath
    }

    /**
     * Parses [filePath] and returns every SonarQube issue key that was already written.
     * Returns an empty set if the file does not exist yet.
     */
    fun readExportedKeys(filePath: String): Set<String> {
        val file = File(filePath)
        if (!file.exists()) return emptySet()
        return ISSUE_KEY_REGEX.findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Appends [content] to [filePath], creating the file and its parent directories
     * if they do not yet exist.
     *
     * @return Absolute path of the file.
     * @throws IOException if the directory cannot be created or the file cannot be written.
     */
    fun append(content: String, filePath: String) {
        val file   = File(filePath)
        val folder = file.parentFile

        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("Cannot create output directory: ${folder.absolutePath}")
        }

        file.appendText(content, Charsets.UTF_8)
        log.info("Appended to Jira ticket file: $filePath  (${content.length} chars)")
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun sanitize(key: String) = key.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
}
