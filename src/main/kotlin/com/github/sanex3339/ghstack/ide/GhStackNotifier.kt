package com.github.sanex3339.ghstack.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

object GhStackNotifier {
    private const val GROUP_ID = "GH Stack"

    fun info(project: Project, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.INFORMATION, actions.toList())

    fun warn(project: Project, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.WARNING, actions.toList())

    /** Errors always offer the console, where the full command output is. */
    fun error(project: Project, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.ERROR, actions.toList() + showConsole(project))

    fun action(text: String, run: () -> Unit): NotificationAction = NotificationAction.createSimpleExpiring(text) { run() }

    private fun showConsole(project: Project): NotificationAction =
        NotificationAction.createSimple("Show console") { GhStackToolWindow.show(project, console = true) }

    private fun notify(project: Project, title: String, content: String, type: NotificationType, actions: List<NotificationAction>) {
        if (project.isDisposed) return
        val html = StringUtil.escapeXmlEntities(content).replace("\n", "<br>")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID).createNotification(title, html, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }
}
