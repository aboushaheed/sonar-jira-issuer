package com.sonarjiraissuer.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent application-level settings for the SonarJira Issuer plugin.
 *
 * Non-sensitive settings are serialised to `sonar-jira-issuer.xml` via IntelliJ's
 * PersistentStateComponent mechanism.
 *
 * The SonarQube token is stored separately in IntelliJ PasswordSafe (OS keychain or
 * KeePass, depending on the IDE configuration) — it is never written to XML.
 */
@Service
@State(
    name     = "SonarJiraIssuerSettings",
    storages = [Storage("sonar-jira-issuer.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    // ── Serialised state ───────────────────────────────────────────────────────

    data class State(
        var serverUrl: String              = "",
        var projectKey: String             = "",
        var selectedIssueTypes: List<String> = listOf("BUG", "CODE_SMELL", "VULNERABILITY"),
        var selectedStatuses: List<String>   = listOf("OPEN"),
        var batchSize: Int                 = 10,
        var titlePrefix: String            = "[PROJECT][TECH][QUALITY]",
        var jiraIssueType: String          = "Task",
        var jiraPriority: String           = "Medium",
        var jiraLabels: String             = "quality,sonar,technical-debt",
        var outputFolder: String           = "",
        // Jira direct creation
        var jiraEnabled: Boolean           = false,
        var jiraServerUrl: String          = "",
        var jiraEmail: String              = "",
        // Remember the last selected Jira project / issue type across sessions
        var jiraProjectKey: String         = "",
        var jiraIssueTypeId: String        = "",
        // Optional epic key to link created tickets to (e.g. "PROJ-42")
        var epicLink: String               = ""
    )

    private var _state = State()

    override fun getState(): State = _state
    override fun loadState(state: State) { XmlSerializerUtil.copyBean(state, _state) }

    // ── Property accessors ─────────────────────────────────────────────────────

    var serverUrl: String
        get() = _state.serverUrl
        set(value) { _state.serverUrl = value }

    var projectKey: String
        get() = _state.projectKey
        set(value) { _state.projectKey = value }

    var selectedIssueTypes: List<String>
        get() = _state.selectedIssueTypes
        set(value) { _state.selectedIssueTypes = value }

    var selectedStatuses: List<String>
        get() = _state.selectedStatuses
        set(value) { _state.selectedStatuses = value }

    var batchSize: Int
        get() = _state.batchSize
        set(value) { _state.batchSize = value }

    var titlePrefix: String
        get() = _state.titlePrefix
        set(value) { _state.titlePrefix = value }

    var jiraIssueType: String
        get() = _state.jiraIssueType
        set(value) { _state.jiraIssueType = value }

    var jiraPriority: String
        get() = _state.jiraPriority
        set(value) { _state.jiraPriority = value }

    var jiraLabels: String
        get() = _state.jiraLabels
        set(value) { _state.jiraLabels = value }

    var outputFolder: String
        get() = _state.outputFolder
        set(value) { _state.outputFolder = value }

    var jiraEnabled: Boolean
        get() = _state.jiraEnabled
        set(value) { _state.jiraEnabled = value }

    var jiraServerUrl: String
        get() = _state.jiraServerUrl
        set(value) { _state.jiraServerUrl = value }

    var jiraEmail: String
        get() = _state.jiraEmail
        set(value) { _state.jiraEmail = value }

    var jiraProjectKey: String
        get() = _state.jiraProjectKey
        set(value) { _state.jiraProjectKey = value }

    var jiraIssueTypeId: String
        get() = _state.jiraIssueTypeId
        set(value) { _state.jiraIssueTypeId = value }

    var epicLink: String
        get() = _state.epicLink
        set(value) { _state.epicLink = value }

    // ── Secure token storage (OS keychain via PasswordSafe) ────────────────────

    /**
     * Saves [token] to IntelliJ PasswordSafe (OS keychain / KeePass).
     * Passing a blank token removes any previously stored credential.
     */
    fun saveToken(token: String) {
        val attrs = credentialAttributes()
        if (token.isBlank()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(TOKEN_KEY, token))
        }
    }

    /**
     * Loads the previously saved token from PasswordSafe.
     * Returns an empty string if no token has been stored yet.
     */
    fun loadSavedToken(): String =
        PasswordSafe.instance.getPassword(credentialAttributes()) ?: ""

    /** Removes the stored token from PasswordSafe. */
    fun clearToken() = PasswordSafe.instance.set(credentialAttributes(), null)

    private fun credentialAttributes() =
        CredentialAttributes(SERVICE_NAME, TOKEN_KEY)

    // ── Jira secure token storage ──────────────────────────────────────────────

    fun saveJiraToken(token: String) {
        val attrs = jiraCredentialAttributes()
        if (token.isBlank()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(JIRA_TOKEN_KEY, token))
        }
    }

    fun loadJiraToken(): String =
        PasswordSafe.instance.getPassword(jiraCredentialAttributes()) ?: ""

    fun clearJiraToken() = PasswordSafe.instance.set(jiraCredentialAttributes(), null)

    private fun jiraCredentialAttributes() =
        CredentialAttributes(JIRA_SERVICE_NAME, JIRA_TOKEN_KEY)

    // ── Companion ──────────────────────────────────────────────────────────────

    companion object {
        // Each token must have its own SERVICE_NAME.
        // Windows Credential Manager (and some other PasswordSafe backends) key on
        // serviceName alone, so sharing the same name causes the second save to
        // overwrite the first.
        private const val SERVICE_NAME       = "SonarJiraIssuer/SonarQube"
        private const val JIRA_SERVICE_NAME  = "SonarJiraIssuer/Jira"
        private const val TOKEN_KEY          = "sonarqube-token"
        private const val JIRA_TOKEN_KEY     = "jira-token"

        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
