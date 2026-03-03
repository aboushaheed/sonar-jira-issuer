package com.sonarjiraissuer.plugin.api

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.Base64

// ── DTOs ──────────────────────────────────────────────────────────────────────

data class JiraUser(
    val displayName: String,
    val emailAddress: String = ""
)

data class JiraProject(
    val id: String,
    val key: String,
    val name: String,
    /** Issue types embedded when the project was loaded with expand=issueTypes. */
    val issueTypes: List<JiraIssueType> = emptyList()
)

data class JiraIssueType(
    val id: String,
    val name: String,
    val description: String = ""
)

data class JiraFieldMeta(
    val key: String,
    val name: String,
    val required: Boolean,
    val schema: String,
    val allowedValues: List<String> = emptyList()
)

data class JiraSprint(
    val id: Int,
    val name: String,
    val state: String   // "active" | "future"
)

// ── Client ────────────────────────────────────────────────────────────────────

/**
 * HTTP client for the Jira REST API built on [HttpURLConnection].
 *
 * Using [HttpURLConnection] instead of `java.net.http.HttpClient` because it
 * automatically uses the JVM's default [java.net.ProxySelector], which IntelliJ
 * Platform replaces with its own proxy-aware implementation — so corporate proxies
 * configured in the IDE settings are honoured without any extra code.
 *
 * ## Server auto-detection
 * - URL contains `atlassian.net` → **Jira Cloud**, REST API **v3**, ADF descriptions
 * - Everything else → **Jira Server / DC**, REST API **v2**, plain-text descriptions
 *
 * ## Authentication
 * - [email] set   → `Basic base64(email:token)` (Jira Cloud API token)
 * - [email] blank → `Bearer token` (Jira Server Personal Access Token)
 */
class JiraClient(
    private val baseUrl: String,
    private val token: String,
    private val email: String = ""
) {
    private val log  = Logger.getInstance(JiraClient::class.java)
    private val gson = Gson()

    /** True when [baseUrl] points to Jira Cloud (*.atlassian.net). */
    val isCloud: Boolean = baseUrl.contains("atlassian.net", ignoreCase = true)

    /** REST API base path — v3 for Cloud, v2 for Server/DC. */
    private val api: String = if (isCloud) "/rest/api/3" else "/rest/api/2"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Validates credentials via GET /rest/api/{v}/myself.
     * @throws JiraApiException on auth failure or network error.
     */
    fun testConnection(): JiraUser {
        val (status, body) = get("$api/myself")
        if (status != 200) throw JiraApiException(status, extractErrorMessage(body))
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: throw JiraApiException(0, "Empty response from server")
        return JiraUser(
            displayName  = map["displayName"]?.toString()  ?: "Unknown",
            emailAddress = map["emailAddress"]?.toString() ?: ""
        )
    }

    /**
     * Returns all projects visible to the authenticated user, optionally filtered by [query].
     *
     * - **No query** → [loadAllProjects]: paginates `/project/search` (Cloud) or calls `/project` (Server)
     * - **With query** → [searchProjects]: server-side filter via `/project/search?query=…`
     */
    fun getProjects(query: String = "", maxResults: Int = 50): List<JiraProject> =
        if (query.isNotBlank()) searchProjects(query, maxResults) else loadAllProjects()

    private fun loadAllProjects(): List<JiraProject> =
        if (isCloud) loadAllProjectsCloud() else loadAllProjectsServer()

    /**
     * Paginates `GET /rest/api/3/project/search` until `isLast = true`
     * to collect every accessible project on Jira Cloud.
     */
    private fun loadAllProjectsCloud(): List<JiraProject> {
        val all      = mutableListOf<JiraProject>()
        var startAt  = 0
        val pageSize = 50

        while (true) {
            val (status, body) = get("$api/project/search?maxResults=$pageSize&startAt=$startAt&expand=issueTypes")
            if (status != 200) {
                log.warn("loadAllProjectsCloud: HTTP $status at startAt=$startAt")
                break
            }
            val page = parseProjects(body)
            all.addAll(page)

            @Suppress("UNCHECKED_CAST")
            val meta   = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            val isLast = (meta?.get("isLast") as? Boolean) ?: true
            val total  = (meta?.get("total")  as? Double)?.toInt() ?: all.size
            log.info("loadAllProjectsCloud: startAt=$startAt page=${page.size} total=$total isLast=$isLast")

            if (isLast || page.isEmpty() || all.size >= total) break
            startAt += page.size
        }

        if (all.isNotEmpty()) return all

        // Fallback: legacy /project endpoint (direct array, no pagination)
        return tryGet("$api/project")
            ?: throw JiraApiException(0, "Could not load Jira projects. Verify your URL and API token.")
    }

    private fun loadAllProjectsServer(): List<JiraProject> =
        tryGet("$api/project?expand=issueTypes")
            ?: tryGet("$api/project")
            ?: tryGet(if (api == "/rest/api/2") "/rest/api/3/project" else "/rest/api/2/project")
            ?: throw JiraApiException(0, "Could not load projects — check Jira URL and token.")

    private fun searchProjects(query: String, maxResults: Int): List<JiraProject> {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val (status, body) = get("$api/project/search?maxResults=$maxResults&query=$q")
        if (status == 200) {
            val results = parseProjects(body)
            if (results.isNotEmpty()) return results
        }
        log.info("searchProjects: server-side search returned nothing, using client-side filter")
        return loadAllProjects().filter {
            it.name.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)
        }
    }

    private fun tryGet(path: String): List<JiraProject>? = try {
        val (status, body) = get(path)
        if (status == 200) parseProjects(body).also { log.info("tryGet $path → ${it.size} projects") }
        else { log.warn("tryGet $path → HTTP $status"); null }
    } catch (e: Exception) {
        log.warn("tryGet $path → ${e.message}")
        null
    }

    /**
     * Returns the issue types for [projectKey].
     * Tries the project-scoped endpoint first, falls back to the global list.
     */
    fun getIssueTypes(projectKey: String): List<JiraIssueType> {
        val (status, body) = get("$api/project/$projectKey/issuetypes")
        if (status == 200) {
            val types = parseIssueTypeArray(body)
            if (types.isNotEmpty()) return types
        }
        log.info("getIssueTypes fallback for $projectKey (primary status $status)")
        val (s2, b2) = get("$api/issuetype")
        if (s2 != 200) throw JiraApiException(s2, extractErrorMessage(b2))
        return parseIssueTypeArray(b2)
    }

    /**
     * Returns field metadata for a given project / issue type pair.
     * Returns an empty list if both strategies fail (metadata is optional).
     */
    fun getCreateMeta(projectKey: String, issueTypeId: String): List<JiraFieldMeta> {
        if (isCloud) {
            val (status, body) = get("$api/issue/createmeta/$projectKey/issuetypes/$issueTypeId/fields")
            if (status == 200) {
                val fields = parseFieldsNewFormat(body)
                if (fields.isNotEmpty()) return fields
            }
        }
        val (status, body) = get(
            "$api/issue/createmeta" +
            "?projectKeys=$projectKey" +
            "&issuetypeIds=$issueTypeId" +
            "&expand=projects.issuetypes.fields"
        )
        if (status != 200) {
            log.warn("getCreateMeta classic failed ($status) — returning empty fields")
            return emptyList()
        }
        return parseFieldsClassicFormat(body)
    }

    /**
     * Creates a Jira issue and returns its key (e.g. `"PROJ-42"`).
     *
     * [description] must already be in the correct format for the target deployment:
     * - Jira Cloud  → ADF `Map<String, Any>` (build via `JiraDescriptionBuilder`)
     * - Jira Server → Jira wiki-markup `String` (build via `JiraDescriptionBuilder`)
     */
    fun createIssue(
        projectKey: String,
        issueTypeId: String,
        summary: String,
        description: Any,
        extraFields: Map<String, Any> = emptyMap()
    ): String {
        val fields = mutableMapOf<String, Any>(
            "project"     to mapOf("key" to projectKey),
            "issuetype"   to mapOf("id"  to issueTypeId),
            "summary"     to summary,
            "description" to description
        )
        fields.putAll(extraFields)

        val (status, body) = post("$api/issue", gson.toJson(mapOf("fields" to fields)))
        if (status !in 200..201) throw JiraApiException(status, extractErrorMessage(body))

        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: throw JiraApiException(0, "Empty response after creating issue")
        return map["key"]?.toString()
            ?: throw JiraApiException(0, "No issue key in response: ${body.take(200)}")
    }

    // ── Team-safe deduplication ────────────────────────────────────────────────

    /**
     * Queries Jira for tickets that carry a `sq-<issueKey>` label for any of the
     * given [sonarIssueKeys], and returns a `sonarIssueKey → jiraTicketKey` map.
     *
     * Each ticket created by this plugin gets one label per SonarQube issue it covers
     * (see [sonarIssueLabel]).  A JQL `labels in (...)` search is therefore a direct,
     * reliable deduplication check — no description parsing required.
     *
     * Issue keys are processed in chunks of 40 to keep JQL queries within safe URL limits.
     */
    fun findSonarMappings(jiraProjectKey: String, sonarIssueKeys: List<String>): Map<String, String> {
        if (sonarIssueKeys.isEmpty()) return emptyMap()

        // Build a reverse map: jira-label → original sonar key
        // (sanitization is one-way, so we can't strip the prefix and get the original key back)
        val labelToKey = sonarIssueKeys.associate { sonarIssueLabel(it) to it }

        val map = mutableMapOf<String, String>()

        sonarIssueKeys.chunked(40).forEach { chunk ->
            val labelList = chunk.joinToString(", ") { "\"${sonarIssueLabel(it)}\"" }
            val jql = "project = \"$jiraProjectKey\" AND labels in ($labelList)"
            val q   = URLEncoder.encode(jql, Charsets.UTF_8)
            var startAt = 0

            while (true) {
                val (status, body) = get("$api/search?jql=$q&fields=key,labels&maxResults=50&startAt=$startAt")
                if (status != 200) { log.warn("findSonarMappings chunk: HTTP $status"); break }

                @Suppress("UNCHECKED_CAST")
                val root   = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: break
                @Suppress("UNCHECKED_CAST")
                val issues = root["issues"] as? List<Map<String, Any>> ?: break

                for (issue in issues) {
                    val jiraKey = issue["key"]?.toString() ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val labels  = ((issue["fields"] as? Map<String, Any>)
                        ?.get("labels") as? List<*>)?.filterIsInstance<String>() ?: continue
                    labels.forEach { label ->
                        val sonarKey = labelToKey[label]
                        if (sonarKey != null) map[sonarKey] = jiraKey
                    }
                }

                val total = (root["total"] as? Double)?.toInt() ?: break
                log.info("findSonarMappings: startAt=$startAt tickets=${issues.size} total=$total mapped=${map.size}")
                if (startAt + issues.size >= total || issues.isEmpty()) break
                startAt += issues.size
            }
        }

        return map
    }

    /**
     * Returns active and future sprints for boards linked to [jiraProjectKey].
     * Uses the Jira Agile REST API (`/rest/agile/1.0/`).
     *
     * Returns an empty list gracefully when the Agile API is unavailable (e.g. Jira Server
     * without Agile licence, or Jira Data Center instances that haven't enabled the endpoint).
     * Results are deduplicated by sprint ID and sorted: active first, then future, both
     * alphabetically by name.
     */
    fun getActiveAndFutureSprints(jiraProjectKey: String): List<JiraSprint> {
        val (boardStatus, boardBody) = try {
            get("/rest/agile/1.0/board?projectKeyOrId=$jiraProjectKey&maxResults=50")
        } catch (e: Exception) {
            log.warn("getActiveAndFutureSprints: board lookup failed: ${e.message}")
            return emptyList()
        }
        if (boardStatus != 200) {
            log.info("getActiveAndFutureSprints: board lookup HTTP $boardStatus — Agile API may not be available")
            return emptyList()
        }

        val boardIds = parseBoards(boardBody)
        if (boardIds.isEmpty()) return emptyList()

        val allSprints = mutableListOf<JiraSprint>()
        for (boardId in boardIds) {
            var startAt = 0
            while (true) {
                val (sprintStatus, sprintBody) = try {
                    get("/rest/agile/1.0/board/$boardId/sprint?state=active,future&maxResults=50&startAt=$startAt")
                } catch (e: Exception) { break }
                if (sprintStatus != 200) break
                val page = parseSprints(sprintBody)
                allSprints.addAll(page)
                @Suppress("UNCHECKED_CAST")
                val isLast = try {
                    (gson.fromJson(sprintBody, Map::class.java) as? Map<String, Any>)
                        ?.get("isLast") as? Boolean ?: true
                } catch (_: Exception) { true }
                if (isLast || page.isEmpty()) break
                startAt += page.size
            }
        }

        return allSprints
            .distinctBy { it.id }
            .sortedWith(compareBy({ if (it.state == "active") 0 else 1 }, { it.name }))
    }

    companion object {
        /** Automatically added to every ticket so [findSonarMappings] can locate them via JQL. */
        const val SONAR_SYNC_LABEL = "sonar-jira-issuer"

        /**
         * Converts a SonarQube issue key to a safe Jira label.
         * Jira labels may not contain spaces, colons, or slashes — replace with `_`.
         * Example: `"my-org:src/Foo.java:uuid"` → `"sq-my-org_src_Foo.java_uuid"`
         */
        fun sonarIssueLabel(sonarKey: String): String =
            "sq-" + sonarKey.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
    }

    // ── Response parsers ───────────────────────────────────────────────────────

    private fun parseBoards(body: String): List<Int> = try {
        @Suppress("UNCHECKED_CAST")
        val root   = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val values = root["values"] as? List<Map<String, Any>> ?: return emptyList()
        values.mapNotNull { b -> (b["id"] as? Double)?.toInt() }
    } catch (_: Exception) { emptyList() }

    private fun parseSprints(body: String): List<JiraSprint> = try {
        @Suppress("UNCHECKED_CAST")
        val root   = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val values = root["values"] as? List<Map<String, Any>> ?: return emptyList()
        values.mapNotNull { s ->
            val id    = (s["id"]    as? Double)?.toInt() ?: return@mapNotNull null
            val name  = s["name"]?.toString()            ?: return@mapNotNull null
            val state = s["state"]?.toString()           ?: return@mapNotNull null
            JiraSprint(id, name, state)
        }
    } catch (_: Exception) { emptyList() }

    private fun parseProjects(body: String): List<JiraProject> {
        @Suppress("UNCHECKED_CAST")
        val raw = gson.fromJson(body, Any::class.java)
        val list: List<Map<String, Any>>? = when {
            raw is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                (raw as Map<String, Any>)["values"] as? List<Map<String, Any>>
            raw is List<*>   -> @Suppress("UNCHECKED_CAST") raw as? List<Map<String, Any>>
            else             -> null
        }
        return list.orEmpty().mapNotNull { p ->
            val key = p["key"]?.toString() ?: return@mapNotNull null
            // Extract issue types embedded via expand=issueTypes (avoids a second API call)
            @Suppress("UNCHECKED_CAST")
            val embeddedTypes = (p["issueTypes"] as? List<Map<String, Any>>)
                ?.mapNotNull { t ->
                    val tid = t["id"]?.toString()   ?: return@mapNotNull null
                    val tname = t["name"]?.toString() ?: return@mapNotNull null
                    // Skip subtasks — they can't be created directly
                    if (t["subtask"] as? Boolean == true) return@mapNotNull null
                    JiraIssueType(id = tid, name = tname, description = t["description"]?.toString() ?: "")
                } ?: emptyList()
            JiraProject(
                id         = p["id"]?.toString() ?: "",
                key        = key,
                name       = p["name"]?.toString() ?: key,
                issueTypes = embeddedTypes
            )
        }
    }

    private fun parseIssueTypeArray(body: String): List<JiraIssueType> = try {
        @Suppress("UNCHECKED_CAST")
        (gson.fromJson(body, List::class.java) as? List<Map<String, Any>>).orEmpty()
            .mapNotNull { t ->
                JiraIssueType(
                    id          = t["id"]?.toString()          ?: return@mapNotNull null,
                    name        = t["name"]?.toString()        ?: return@mapNotNull null,
                    description = t["description"]?.toString() ?: ""
                )
            }
    } catch (_: Exception) { emptyList() }

    private fun parseFieldsNewFormat(body: String): List<JiraFieldMeta> {
        @Suppress("UNCHECKED_CAST")
        val root   = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val fields = root["fields"] as? List<Map<String, Any>> ?: return emptyList()
        return fields.mapNotNull { f ->
            val key  = f["fieldId"]?.toString() ?: f["key"]?.toString() ?: return@mapNotNull null
            val name = f["name"]?.toString() ?: key
            val req  = (f["required"] as? Boolean) ?: false
            @Suppress("UNCHECKED_CAST")
            val schema  = (f["schema"] as? Map<String, Any>)?.get("type")?.toString() ?: "string"
            @Suppress("UNCHECKED_CAST")
            val allowed = (f["allowedValues"] as? List<Map<String, Any>>)
                ?.mapNotNull { av -> av["name"]?.toString() ?: av["value"]?.toString() }
                ?: emptyList()
            JiraFieldMeta(key, name, req, schema, allowed)
        }.sortedWith(compareByDescending<JiraFieldMeta> { it.required }.thenBy { it.name })
    }

    private fun parseFieldsClassicFormat(body: String): List<JiraFieldMeta> {
        @Suppress("UNCHECKED_CAST")
        val root = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val projects   = root["projects"]   as? List<Map<String, Any>> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val issueTypes = (projects.firstOrNull() ?: return emptyList())["issuetypes"]
                as? List<Map<String, Any>> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val fields     = (issueTypes.firstOrNull() ?: return emptyList())["fields"]
                as? Map<String, Map<String, Any>> ?: return emptyList()
        return fields.entries.mapNotNull { (key, meta) ->
            val name   = meta["name"]?.toString() ?: key
            val req    = (meta["required"] as? Boolean) ?: false
            @Suppress("UNCHECKED_CAST")
            val schema = (meta["schema"] as? Map<String, Any>)?.get("type")?.toString() ?: "string"
            @Suppress("UNCHECKED_CAST")
            val allowed = (meta["allowedValues"] as? List<Map<String, Any>>)
                ?.mapNotNull { av -> av["name"]?.toString() ?: av["value"]?.toString() }
                ?: emptyList()
            JiraFieldMeta(key, name, req, schema, allowed)
        }.sortedWith(compareByDescending<JiraFieldMeta> { it.required }.thenBy { it.name })
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private fun get(path: String): Pair<Int, String>           = request(path, "GET",  null)
    private fun post(path: String, body: String): Pair<Int, String> = request(path, "POST", body)

    /**
     * Executes an HTTP request via [HttpURLConnection].
     *
     * [HttpURLConnection] respects the JVM's default [java.net.ProxySelector].
     * IntelliJ Platform replaces this with its own implementation that reads the
     * IDE proxy settings — so corporate proxies are honoured automatically.
     */
    private fun request(path: String, method: String, body: String?): Pair<Int, String> {
        val url  = if (path.startsWith("http")) path else "${normalizedBaseUrl()}$path"
        val conn = java.net.URL(url).openConnection() as? HttpURLConnection
            ?: throw JiraApiException(0, "Cannot open connection to $url")
        return try {
            conn.requestMethod           = method
            conn.connectTimeout          = 10_000
            conn.readTimeout             = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Authorization", authHeader())
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val responseBody = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            }
            Pair(status, responseBody)
        } catch (e: IOException) {
            throw JiraApiException(0, "Network error connecting to $url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun authHeader(): String =
        if (email.isNotBlank()) {
            "Basic ${Base64.getEncoder().encodeToString("$email:$token".toByteArray(Charsets.UTF_8))}"
        } else {
            "Bearer $token"
        }

    private fun extractErrorMessage(body: String): String = try {
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(body, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        (map["errorMessages"] as? List<String>)?.firstOrNull()
            ?: map["message"]?.toString()
            ?: body.take(200)
    } catch (_: Exception) { body.take(200) }

    private fun normalizedBaseUrl(): String = baseUrl.trimEnd('/')
}

// ── Exception ─────────────────────────────────────────────────────────────────

class JiraApiException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    fun userMessage(): String = when (statusCode) {
        0    -> "Network error — check the Jira URL and your internet connection."
        401  -> "Authentication failed — verify your email and API token."
        403  -> "Access denied — your token does not have permission to access Jira."
        404  -> "Not found — check that the Jira URL is correct."
        408  -> "Request timed out — the server took too long to respond."
        429  -> "Rate limit exceeded — please wait a moment and try again."
        500  -> "Jira server error (HTTP 500) — check the server status."
        503  -> "Jira is unavailable (HTTP 503) — the server may be down."
        else -> "Jira API error (HTTP $statusCode): $message"
    }
}
