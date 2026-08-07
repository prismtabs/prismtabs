package io.chromatabs.provider

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.chromatabs.service.TabGroupService
import java.awt.Color

/**
 * `order="first"` (in plugin.xml) and `DumbAware` are load-bearing, not cosmetic — without
 * `order="first"` the platform's own File Colors scopes win first and this provider is never
 * consulted; without `DumbAware` this is silently skipped during indexing. See README §"What gets
 * deleted in Phase 1" for the Phase 0 finding this carries forward.
 */
internal class TabGroupColorProvider : EditorTabColorProvider, DumbAware {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        project.service<TabGroupService>().colorFor(file.url)
}
