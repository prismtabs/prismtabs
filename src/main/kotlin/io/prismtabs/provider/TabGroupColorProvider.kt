package io.prismtabs.provider

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupService
import java.awt.Color

/**
 * `order="first"` (in plugin.xml) and `DumbAware` are load-bearing, not cosmetic — without
 * `order="first"` the platform's own File Colors scopes win first and this provider is never
 * consulted; without `DumbAware` this is silently skipped during indexing.
 *
 * The `JBColor(light, dark)` is built here, not cached — this is the one place in the plugin that
 * is safe to touch `JBColor`'s theme-detection machinery (it always runs inside a live IDE with an
 * active LAF), which is exactly why [TabGroupService] hands back a plain [TabGroup] instead of a
 * resolved colour: doing it here means a theme switch re-tints the tab on the next repaint with no
 * cache invalidation needed.
 */
internal class TabGroupColorProvider : EditorTabColorProvider, DumbAware {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        colorOf(project.service<TabGroupService>().groupFor(file.url))

    internal companion object {

        /**
         * Whether this plugin still answers for tab colours at all. One-way, and false only from
         * [stop] — there is no path back, because the only thing that closes it is this plugin
         * being unloaded.
         *
         * `@Volatile` for the same reason [TabGroupService.groupFor]'s caches are: this is read on
         * the EDT during tab painting and written from whichever thread the platform runs
         * `beforePluginUnload` on.
         */
        @Volatile
        private var colouring = true

        /**
         * The colour [group] earns a tab, or null — for an unassigned file, and for *every* file
         * once [stop] has been called.
         *
         * Split out from [getEditorTabColor] so the gate can be exercised without a live IDE: the
         * override itself needs a [Project] to reach [TabGroupService], and this does not.
         */
        fun colorOf(group: TabGroup?): Color? =
            if (!colouring || group == null) null else JBColor(Color(group.lightRgb), Color(group.darkRgb))

        /**
         * Stops handing out colours, so that a repaint after this point leaves every tab the colour
         * the platform's own providers give it.
         *
         * Called from [io.prismtabs.listener.PrismTabsDynamicPluginListener] on uninstall, update
         * and disable. The platform *does* unregister this extension point as part of the unload,
         * but it repaints nothing afterwards, so without both halves — this gate and the repaint
         * the listener asks for next — every tab keeps the colour of a plugin that is no longer
         * there until the user restarts the IDE, which on a disable they are never prompted to do.
         *
         * A gate rather than relying on the unregistration alone because the ordering is not ours
         * to depend on: the repaint is queued, and whether it drains before or after the platform
         * drops the extension point decides whether this provider is asked one last time.
         */
        fun stop() {
            colouring = false
        }
    }
}
