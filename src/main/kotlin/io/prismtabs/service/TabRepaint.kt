package io.prismtabs.service

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import io.prismtabs.provider.TabGroupColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color

/**
 * Forces the editor tab strip to show a file's current group colour — the live half of this
 * plugin's whole feature, and the one thing `editorTabColorProvider` gives no way to ask for.
 *
 * **Why this is hand-rolled.** The platform caches a tab's background on its [TabInfo] and only
 * recomputes it on its own schedule, so a group recoloured, joined, left or deleted while its tabs
 * are open would otherwise keep the old colour until something unrelated refreshed that tab. The
 * platform's own trigger is `FileEditorManagerImpl.queueUpdateFile`, which is `@ApiStatus.Internal`
 * — Marketplace refuses an upload over it, which is what happened to 1.4.14. Asked for publicly as
 * [IJPL-256289](https://youtrack.jetbrains.com/issue/IJPL-256289).
 *
 * **What this does instead** is `EditorsSplitters.updateFileBackgroundColor`, step for step, over
 * members that are all public, unmangled and unannotated on every IDE from 2024.2 to 263 (checked
 * with `javap` per CLAUDE.md on both ends):
 *
 * ```
 * FileEditorManagerEx.windows      EditorWindow[], over every splitter — detached windows included
 *   window.getComposite(file)      that window's EditorComposite for the file, or null
 *   composite.component            the EditorCompositePanel the tab is built around
 *   window.tabbedPane.tabs.tabs    that window's TabInfos
 *   TabInfo.setTabColor(colour)
 * ```
 *
 * A [TabInfo] is matched by component identity because the tab-to-file map is not public:
 * `EditorComposite.getComponent()` returns the very `EditorCompositePanel` that `TabInfo
 * .getComponent()` holds (identical on 2024.2 and 263), so the two compare equal by reference. The
 * platform's own `TabInfo.composite` does this lookup in reverse and is Kotlin-`internal`, which is
 * why it is not used.
 *
 * **Where the colour comes from, and why it is not always the same source.** This plugin registers
 * `order="first"`, and `EditorTabPresentationUtil.getEditorTabBackgroundColor` returns the first
 * non-null answer — so whenever this plugin claims a file, its own colour *is* the final answer and
 * is set directly. That matters for more than speed: asking the full provider chain reaches the
 * platform's File Colors provider, which hits the workspace file index, and on the EDT that trips
 * `SlowOperations.assertSlowOperationsAreAllowed` — measured, as four logged errors in
 * `TabGroupPopupUiTest`, not predicted. The chain is therefore consulted only where it has to be:
 * when this plugin has *stopped* claiming a file and something else may want to colour it. That
 * case alone goes through [recomputeOffEdt], which mirrors the platform exactly — `readAction` to
 * compute, `Dispatchers.EDT` to apply.
 *
 * `setTabColor` fires a `"color"` property change only when the value actually differs, so a
 * repaint that changes nothing costs nothing rather than flickering.
 */
internal object TabRepaint {

    /**
     * Repaints [file]'s tabs for a membership or colour change that has already been applied to
     * [TabGroupService].
     *
     * Clearing is done immediately and the accurate fallback asynchronously, in that order, so a
     * tab this plugin has just stopped claiming loses its colour at once rather than holding it
     * until a background read finishes.
     */
    fun request(project: Project, file: VirtualFile) {
        val ours = ourColour(project, file)
        forEachTabOf(project, file) { it.setTabColor(ours) }
        if (ours == null) project.service<Repainter>().recomputeOffEdt(file)
    }

    /**
     * Every tab this plugin claims, repainted from its own state alone — what a dynamic load or
     * unload needs, the one caller that cannot name its files:
     * [io.prismtabs.listener.PrismTabsDynamicPluginListener] is repainting *because* this plugin
     * has just started, or is about to stop, answering for all of them.
     *
     * Synchronous, and deliberately so: at unload this has to finish before the class loader goes,
     * and a coroutine of this plugin's own would be cancelled with it. It can be synchronous
     * precisely because it never consults the provider chain — after
     * [TabGroupColorProvider.stop] every answer is `null`, which is exactly "take this plugin's
     * colour off the strip". Tabs this plugin never claimed are left alone, so another provider's
     * colour is not collateral damage.
     */
    fun requestAll(project: Project) {
        val service = project.service<TabGroupService>()
        forEachTab(project) { file, tab ->
            val group = service.groupFor(file.url) ?: return@forEachTab
            tab.setTabColor(TabGroupColorProvider.colorOf(group))
        }
    }

    /** This plugin's own answer for [file], which wins outright when it is not null. */
    private fun ourColour(project: Project, file: VirtualFile): Color? =
        TabGroupColorProvider.colorOf(project.service<TabGroupService>().groupFor(file.url))

    private inline fun forEachTabOf(project: Project, file: VirtualFile, action: (TabInfo) -> Unit) {
        windows(project).forEach { window ->
            val component = window.getComposite(file)?.component ?: return@forEach
            window.tabbedPane.tabs.tabs.firstOrNull { it.component === component }?.let(action)
        }
    }

    private inline fun forEachTab(project: Project, action: (VirtualFile, TabInfo) -> Unit) {
        windows(project).forEach { window ->
            window.fileList.forEach { file ->
                val component = window.getComposite(file)?.component ?: return@forEach
                window.tabbedPane.tabs.tabs.firstOrNull { it.component === component }
                    ?.let { action(file, it) }
            }
        }
    }

    /**
     * Every editor window of [project], over every splitter — `windows` is derived from
     * `getAllSplitters()`, so a file dragged out into its own frame is repainted too. Guarded
     * because a headless fixture has no editor manager to ask.
     */
    private fun windows(project: Project): List<EditorWindow> =
        runCatching { FileEditorManagerEx.getInstanceEx(project).windows.toList() }.getOrDefault(emptyList())

    /**
     * Holds the scope the one asynchronous case needs. A project-level service rather than a field
     * on [TabRepaint] so the scope is the platform's to cancel — with the project, and with this
     * plugin — instead of something this object has to own and dispose of itself.
     */
    @Service(Service.Level.PROJECT)
    internal class Repainter(private val project: Project, private val scope: CoroutineScope) {

        /**
         * Re-asks the whole provider chain for [file] and applies the answer, off the EDT.
         *
         * Only reached once this plugin has stopped claiming [file], so the answer is another
         * provider's colour or none. `readAction` then `Dispatchers.EDT` is what
         * `EditorsSplitters.updateFileBackgroundColor` itself does, and the reason is the same: the
         * File Colors provider in that chain reads the workspace file index, which is a slow
         * operation and forbidden on the EDT.
         */
        fun recomputeOffEdt(file: VirtualFile) {
            scope.launch {
                val colour = readAction { EditorTabPresentationUtil.getEditorTabBackgroundColor(project, file) }
                withContext(Dispatchers.EDT) {
                    forEachTabOf(project, file) { it.setTabColor(colour) }
                }
            }
        }
    }
}
