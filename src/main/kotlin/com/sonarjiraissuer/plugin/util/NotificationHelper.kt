package com.sonarjiraissuer.plugin.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Thin wrapper around IntelliJ's notification system.
 * All user-facing messages are in English.
 */
object NotificationHelper {

    private const val GROUP_ID = "SonarJira Issuer"

    fun notifyInfo(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.INFORMATION)

    fun notifyWarning(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.WARNING)

    fun notifyError(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.ERROR)

    private fun notify(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
