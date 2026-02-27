package com.sonarjiraissuer.plugin.model

/**
 * SonarQube issue types supported by the plugin.
 * [apiValue] is the exact string sent to the SonarQube API.
 */
enum class IssueType(val apiValue: String, val displayName: String) {
    BUG("BUG", "Bug"),
    VULNERABILITY("VULNERABILITY", "Vulnerability"),
    CODE_SMELL("CODE_SMELL", "Code Smell"),
    SECURITY_HOTSPOT("SECURITY_HOTSPOT", "Security Hotspot");

    override fun toString(): String = displayName
}
