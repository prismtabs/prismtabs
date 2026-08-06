package io.chromatabs.phase0

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx

/**
 * PHASE 0 ONLY — throwaway diagnostic.
 *
 * The implementation plan flags one unknown: *which* call forces the editor tab
 * strip to repaint after a colour changes. The answer differs across platform
 * versions and some candidates have been deprecated.
 *
 * Rather than guess a method name, this action reflects over FileEditorManagerEx
 * on the running IDE and reports every plausible candidate. Run it once per
 * platform version you intend to support, then hard-code the winner in Phase 1
 * and delete this class. Do not ship reflection in the real implementation.
 */
internal class RepaintApiProbeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = FileEditorManagerEx.getInstanceEx(project)

        val keywords = listOf("refresh", "update", "present", "repaint", "tab")
        val candidates = manager.javaClass.methods
            .filter { method -> keywords.any { method.name.lowercase().contains(it) } }
            .filter { it.parameterCount <= 1 }
            .map { method ->
                val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                "${method.name}($params)"
            }
            .distinct()
            .sorted()

        val report = buildString {
            appendLine("Platform build: ${com.intellij.openapi.application.ApplicationInfo.getInstance().build}")
            appendLine("FileEditorManagerEx impl: ${manager.javaClass.name}")
            appendLine("Repaint API candidates (${candidates.size}):")
            candidates.forEach { appendLine("  $it") }
        }

        thisLogger().warn("[Chromatabs probe]\n$report")

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Chromatabs")
            .createNotification(
                "Chromatabs: repaint API probe",
                "Found ${candidates.size} candidates. Full list written to idea.log — " +
                    "search for \"[Chromatabs probe]\".",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
