package io.prismtabs.provider

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.prismtabs.service.TabGroupService
import java.awt.Color

/**
 * `order="first"` (in plugin.xml) and `DumbAware` are load-bearing, not cosmetic — without
 * `order="first"` the platform's own File Colors scopes win first and this provider is never
 * consulted; without `DumbAware` this is silently skipped during indexing. See README §"What gets
 * deleted in Phase 1" for the Phase 0 finding this carries forward.
 *
 * The `JBColor(light, dark)` is built here, not cached — this is the one place in the plugin that
 * is safe to touch `JBColor`'s theme-detection machinery (it always runs inside a live IDE with an
 * active LAF), which is exactly why [TabGroupService] hands back a plain [io.prismtabs.model.TabGroup]
 * instead of a resolved colour: doing it here means a theme switch re-tints the tab on the next
 * repaint with no cache invalidation needed.
 */
internal class TabGroupColorProvider : EditorTabColorProvider, DumbAware {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        project.service<TabGroupService>().groupFor(file.url)?.let { group ->
            JBColor(Color(group.lightRgb), Color(group.darkRgb))
        }
}
