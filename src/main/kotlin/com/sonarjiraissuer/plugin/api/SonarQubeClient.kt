package com.sonarjiraissuer.plugin.api

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sonarjiraissuer.plugin.api.dto.SonarIssue
import com.sonarjiraissuer.plugin.api.dto.SonarIssuesResponse
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Represents a SonarQube project returned by the components/search endpoint.
 */
data class SonarProject(
    val key: String,
    val name: String
)

/**
 * HTTP client for the SonarQube REST API.
 *
 * Authentication is done via a Bearer token header. The token is never
 * logged, stored on disk, or included in exception messages.
 *
 * Usage:
 * ```kotlin
 * val client = SonarQubeClient("https://sonarcloud.io", myToken)
 * client.validateConnection()
 * val issues = client.fetchAllIssues("my-org_my-project", listOf("BUG", "CODE_SMELL"))
 * val projects = client.searchProjects()
 * ```
 */
class SonarQubeClient(
    private val baseUrl: String,
    private val token: String
) {
    private val log = Logger.getInstance(SonarQubeClient::class.java)
    private val gson = Gson()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    companion object {
        private const val PAGE_SIZE    = 100
        /** SonarQube hard cap: max 10 000 results per query (page 100 × 100 items). */
        private const val MAX_PAGES    = 100
        private const val ISSUES_PATH   = "/api/issues/search"
        private const val VALIDATE_PATH = "/api/authentication/validate"
        private const val PROJECTS_PATH = "/api/components/search"

        /**
         * Build a direct browser link to a SonarQube issue.
         * Format: {baseUrl}/project/issues?id={projectKey}&open={issueKey}
         */
        fun buildIssueLink(baseUrl: String, projectKey: String, issueKey: String): String {
            val base = baseUrl.trimEnd('/')
            return "$base/project/issues?id=${encode(projectKey)}&open=${encode(issueKey)}"
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Validates connectivity and token by calling `/api/authentication/validate`.
     *
     * Uses [java.net.HttpURLConnection] (not [java.net.http.HttpClient]) so that
     * IntelliJ's proxy settings are honoured automatically — same reason as [JiraClient].
     *
     * SonarQube returns HTTP 200 + `{"valid":true}` for a valid token, and
     * HTTP 200 + `{"valid":false}` for an anonymous / invalid token, so we must
     * check the response body, not just the HTTP status.
     *
     * @throws SonarApiException if the server is unreachable or the token is invalid.
     */
    fun validateConnection() {
        val url  = "${normalizedBaseUrl()}$VALIDATE_PATH"
        val conn = java.net.URL(url).openConnection() as? java.net.HttpURLConnection
            ?: throw SonarApiException(0, "Cannot open connection to $url")
        try {
            conn.connectTimeout          = 10_000
            conn.readTimeout             = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            val body   = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            }
            if (status != 200) throw SonarApiException(status, "Authentication check failed")
            // {"valid":true} = ok, {"valid":false} = anonymous/bad token (both are HTTP 200)
            @Suppress("UNCHECKED_CAST")
            val map   = try { gson.fromJson(body, Map::class.java) as? Map<String, Any> } catch (_: Exception) { null }
            val valid = map?.get("valid") as? Boolean
            if (valid == false) throw SonarApiException(401, "Token is invalid or has insufficient permissions")
        } catch (e: java.io.IOException) {
            throw SonarApiException(0, "Network error connecting to $url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Searches for projects accessible with the current token.
     *
     * Uses `/api/components/search?qualifiers=TRK` which respects user permissions —
     * only projects the token can access are returned.
     *
     * @param query Optional search term to filter by project name or key.
     * @return Up to 100 matching projects.
     * @throws SonarApiException on HTTP errors or network failures.
     */
    fun searchProjects(query: String = ""): List<SonarProject> {
        val path = buildString {
            append("$PROJECTS_PATH?qualifiers=TRK&ps=100")
            if (query.isNotBlank()) append("&q=${encode(query)}")
        }
        val response = execute(buildRequest(path))
        if (response.statusCode() != 200) {
            throw SonarApiException(response.statusCode(), extractErrorMessage(response.body()))
        }

        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(response.body(), Map::class.java) as? Map<String, Any>
            ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val components = map["components"] as? List<Map<String, Any>> ?: return emptyList()

        return components.mapNotNull { c ->
            val key = c["key"]?.toString() ?: return@mapNotNull null
            SonarProject(
                key  = key,
                name = c["name"]?.toString() ?: key
            )
        }
    }

    /**
     * Fetches **all** issues matching the given filters, handling SonarQube pagination
     * transparently.
     *
     * @param projectKey   SonarQube project key (e.g. "my-org_my-project").
     * @param issueTypes   List of SonarQube type strings (e.g. ["BUG", "CODE_SMELL"]).
     * @param statuses     List of statuses to include (default: ["OPEN"]).
     * @param onProgress   Optional callback invoked after each page with (fetched, total).
     * @throws SonarApiException on HTTP errors or network failures.
     */
    fun fetchAllIssues(
        projectKey: String,
        issueTypes: List<String>,
        statuses: List<String> = listOf("OPEN"),
        onProgress: ((fetched: Int, total: Int) -> Unit)? = null
    ): List<SonarIssue> {
        val allIssues   = mutableListOf<SonarIssue>()
        var page        = 1
        var totalIssues = Int.MAX_VALUE

        while (allIssues.size < totalIssues && page <= MAX_PAGES) {
            val url      = buildSearchUrl(projectKey, issueTypes, statuses, page)
            log.info("Fetching SonarQube page $page: $url")

            val response = execute(buildRequest(url))
            if (response.statusCode() != 200) {
                throw SonarApiException(
                    response.statusCode(),
                    extractErrorMessage(response.body())
                )
            }

            val parsed      = gson.fromJson(response.body(), SonarIssuesResponse::class.java)
            totalIssues     = parsed.totalCount()

            if (parsed.issues.isEmpty()) break
            allIssues.addAll(parsed.issues)

            log.info("Page $page: +${parsed.issues.size} issues (${allIssues.size}/$totalIssues total)")
            onProgress?.invoke(allIssues.size, totalIssues)

            if (!parsed.hasMorePages()) break
            page++
        }

        log.info("Fetched ${allIssues.size} issues total for project '$projectKey'")
        return allIssues
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildSearchUrl(
        projectKey: String,
        issueTypes: List<String>,
        statuses: List<String>,
        page: Int
    ): String = buildString {
        append(normalizedBaseUrl())
        append(ISSUES_PATH)
        append("?componentKeys=${encode(projectKey)}")
        if (issueTypes.isNotEmpty()) {
            append("&types=${encode(issueTypes.joinToString(","))}")
        }
        if (statuses.isNotEmpty()) {
            append("&statuses=${encode(statuses.joinToString(","))}")
        }
        append("&additionalFields=_all")
        append("&p=$page")
        append("&ps=$PAGE_SIZE")
    }

    private fun buildRequest(path: String): HttpRequest {
        val fullUrl = if (path.startsWith("http")) path else "${normalizedBaseUrl()}$path"
        return HttpRequest.newBuilder()
            .uri(URI.create(fullUrl))
            .timeout(Duration.ofSeconds(30))
            // Token deliberately not logged anywhere
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .GET()
            .build()
    }

    private fun execute(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw SonarApiException(0, "Network error: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SonarApiException(0, "Request was interrupted", e)
        }
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(body, Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val errors = map["errors"] as? List<Map<String, String>>
            errors?.firstOrNull()?.get("msg") ?: body
        } catch (_: Exception) {
            body.take(200)
        }
    }

    private fun normalizedBaseUrl(): String = baseUrl.trimEnd('/')
}

/**
 * Signals an error from the SonarQube API.
 *
 * @property statusCode  HTTP status code (0 for network-level errors).
 */
class SonarApiException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    /** Returns a user-friendly description of the error. */
    fun userMessage(): String = when (statusCode) {
        0    -> "Network error — check the SonarQube URL and your internet connection."
        401  -> "Authentication failed — please verify your SonarQube token."
        403  -> "Access denied — your token does not have permission to access this project."
        404  -> "Not found — check that the SonarQube URL and project key are correct."
        408  -> "Request timed out — the server took too long to respond."
        429  -> "Rate limit exceeded — too many requests. Please wait a moment and try again."
        500  -> "SonarQube server error (HTTP 500) — check the server status."
        503  -> "SonarQube is unavailable (HTTP 503) — the server may be down or under maintenance."
        else -> "SonarQube API error (HTTP $statusCode): $message"
    }
}
