package com.sonarjiraissuer.plugin.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException

/**
 * Persists a mapping of SonarQube issue keys → Jira ticket keys to a local JSON file.
 *
 * This prevents creating duplicate Jira tickets when the user runs the plugin
 * multiple times on the same project.
 *
 * ## File format
 * ```json
 * {
 *   "AXz12abc": "PROJ-42",
 *   "AXz13xyz": "PROJ-42",
 *   "AXz14def": "PROJ-43"
 * }
 * ```
 * Multiple SonarQube issue keys can map to the same Jira ticket key because one
 * Jira ticket covers a batch of issues.
 *
 * ## Deduplication strategy
 * - An [IssueGroup] is considered "already created" when **all** its issues are
 *   present in the map.
 * - A group where only some issues are mapped is treated as new (different batch
 *   size between runs) and will be created; this is logged as a warning.
 */
object JiraIssueMappingService {

    private val log  = Logger.getInstance(JiraIssueMappingService::class.java)
    private val gson = Gson()

    private const val MAP_FILE = ".sonar-jira-map.json"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the canonical path to the map file for [projectKey] inside [baseDir].
     */
    fun resolveMapFile(baseDir: String, projectKey: String): String {
        val safeKey = sanitize(projectKey)
        return File(baseDir, ".sonar-jira-map_$safeKey.json").absolutePath
    }

    /**
     * Loads all previously recorded mappings from [filePath].
     * Returns an empty map if the file does not exist or cannot be parsed.
     */
    fun readMappings(filePath: String): Map<String, String> {
        val file = File(filePath)
        if (!file.exists()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(file.readText(Charsets.UTF_8), type) ?: emptyMap()
        } catch (e: Exception) {
            log.warn("Could not parse Jira mapping file '$filePath': ${e.message}")
            emptyMap()
        }
    }

    /**
     * Adds [newMappings] (sonarKey → jiraKey) to the persisted map at [filePath],
     * merging with any already-stored entries. Creates the file if absent.
     *
     * @throws IOException if the file cannot be written.
     */
    fun addMappings(filePath: String, newMappings: Map<String, String>) {
        if (newMappings.isEmpty()) return
        val merged = readMappings(filePath).toMutableMap()
        merged.putAll(newMappings)
        writeMappings(filePath, merged)
        log.info("Saved ${newMappings.size} new Jira mapping(s) to '$filePath'")
    }

    /**
     * Returns the subset of [sonarKeys] that already appear in the stored map.
     * Keys not yet mapped are simply absent from the result.
     */
    fun alreadyMapped(sonarKeys: Collection<String>, mappings: Map<String, String>): Map<String, String> =
        sonarKeys.mapNotNull { k -> mappings[k]?.let { k to it } }.toMap()

    // ── Private ────────────────────────────────────────────────────────────────

    private fun writeMappings(filePath: String, mappings: Map<String, String>) {
        val file   = File(filePath)
        val folder = file.parentFile
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("Cannot create directory: ${folder.absolutePath}")
        }
        file.writeText(gson.toJson(mappings), Charsets.UTF_8)
    }

    private fun sanitize(key: String) = key.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
}
