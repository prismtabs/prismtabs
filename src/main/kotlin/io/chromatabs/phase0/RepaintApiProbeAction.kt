package io.chromatabs.phase0

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.ui.ExperimentalUI
import com.intellij.util.ui.JBUI

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

        // Empirical test of the repaint candidates. Bytecode inspection of 2024.2 shows the two
        // are NOT equivalent:
        //   updateFileColor  -> EditorsSplitters.scheduleUpdateFileColor -> foreground/attributes only
        //   queueUpdateFile  -> fileUpdateChannel -> updateFileBackgroundColor -> TabInfo.setTabColor
        // Only the latter can move the tab BACKGROUND, which is what this plugin tints.
        // Both are declared on FileEditorManagerImpl, not the FileEditorManagerEx interface, hence
        // reflection. This is throwaway probe code — Phase 1 must not ship reflection.
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val invokeResult = if (file != null) {
            listOf("queueUpdateFile", "updateFileColor").joinToString("\n") { name ->
                try {
                    val method = manager.javaClass.getMethod(
                        name,
                        com.intellij.openapi.vfs.VirtualFile::class.java,
                    )
                    method.invoke(manager, file)
                    "$name(${file.name}) invoked successfully"
                } catch (ex: Exception) {
                    "$name(${file.name}) failed: ${ex.javaClass.simpleName}: ${ex.message}"
                }
            }
        } else {
            "no VirtualFile in DataContext — skipped repaint calls"
        }

        // Where does the colour actually get lost? Three observation points along the chain:
        //   1. what the platform aggregates from ALL providers (another provider could win),
        //   2. what actually landed on the TabInfo that the painter reads,
        //   3. whether the New UI / theme is in play, since the painter blends differently there.
        val diagnostics = if (file != null) buildDiagnostics(manager, project, file) else "no file"

        val report = buildString {
            appendLine("Platform build: ${com.intellij.openapi.application.ApplicationInfo.getInstance().build}")
            appendLine("FileEditorManagerEx impl: ${manager.javaClass.name}")
            appendLine("Repaint API candidates (${candidates.size}):")
            candidates.forEach { appendLine("  $it") }
            appendLine(invokeResult)
            appendLine(diagnostics)
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

    /**
     * Observation points along the colour chain, so we can say *where* the colour is lost
     * rather than guessing:
     *
     *  1. `EditorTabPresentationUtil.getEditorTabBackgroundColor` — what the platform aggregates
     *     across ALL registered providers. If this is null while our provider logs a colour, some
     *     other provider or the dumb-aware filter is winning.
     *  2. `TabInfo.tabColor` — what actually landed on the object the painter reads. If (1) has a
     *     colour and this is null, the apply step is the problem, not the provider.
     *  3. New UI / theme state — the painter blends unselected coloured tabs against
     *     `inactiveColoredFileBackground`, so the theme decides how visible any tint is.
     *
     * All reflection here is throwaway diagnostics; none of it may ship in Phase 1.
     */
    private fun buildDiagnostics(
        manager: FileEditorManagerEx,
        project: com.intellij.openapi.project.Project,
        file: com.intellij.openapi.vfs.VirtualFile,
    ): String = buildString {
        val aggregated = runCatching {
            EditorTabPresentationUtil.getEditorTabBackgroundColor(project, file)
        }
        appendLine("aggregated getEditorTabBackgroundColor: ${aggregated.getOrNull() ?: aggregated.exceptionOrNull() ?: "null"}")

        val tabColour = runCatching {
            // FileEditorManagerEx.getWindows() is the public accessor; EditorsSplitters.windows
            // is private in 2024.2.
            val windows = manager.windows.toList()
            if (windows.isEmpty()) {
                "no editor windows"
            } else {
                windows.joinToString("; ") { window ->
                    val findTab = window.javaClass.methods.firstOrNull { it.name.startsWith("findTabByFile") }
                        ?: return@joinToString "findTabByFile not found"
                    val tabInfo = findTab.invoke(window, file)
                        ?: return@joinToString "no TabInfo for ${file.name}"
                    val getTabColor = tabInfo.javaClass.getMethod("getTabColor")
                    "TabInfo.tabColor=${getTabColor.invoke(tabInfo)}"
                }
            }
        }
        appendLine("tab state: ${tabColour.getOrNull() ?: tabColour.exceptionOrNull()}")

        appendLine("isNewUI=${ExperimentalUI.isNewUI()}")
        appendLine("LaF=${javax.swing.UIManager.getLookAndFeel()?.name}")
        appendLine("inactiveColoredFileBackground=${runCatching { JBUI.CurrentTheme.EditorTabs.inactiveColoredFileBackground() }.getOrNull()}")
    }
}
