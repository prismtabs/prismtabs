package io.chromatabs.phase0

import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * PHASE 0 ONLY — throwaway.
 *
 * Purpose: prove that a colour returned from this extension point is actually
 * painted on the editor tab in *this* IDE version and *this* theme.
 *
 * Tints any file whose name contains "Test", and any .md file.
 *
 * IMPORTANT (carries forward to the real implementation):
 * this method is called on the EDT during tab painting, frequently. It must stay
 * a cheap lookup — no read actions, no PSI, no VFS I/O, no locks.
 *
 * DumbAware is REQUIRED, not optional. The platform iterates this EP through
 * `lazyDumbAwareExtensions(EP_NAME, project)`, which filters every extension through
 * `DumbService.isUsableInCurrentContext`. `EditorTabColorProvider` extends `PossiblyDumbAware`,
 * whose default `isDumbAware()` is `this instanceof DumbAware` — so without this marker the
 * provider is silently skipped during indexing, and any colour recompute in that window
 * resets the tab to no colour. A pure map lookup is genuinely dumb-safe, so the marker is honest.
 */
internal class Phase0ColorProvider : EditorTabColorProvider, DumbAware {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        when (Phase0Rules.tintFor(file.name)) {
            Phase0Rules.Tint.TEST -> TEST_TINT
            Phase0Rules.Tint.MARKDOWN -> MARKDOWN_TINT
            Phase0Rules.Tint.NONE -> null
        }

    private companion object {
        // Saturation here is a measured requirement, not taste. The platform blends UNSELECTED
        // coloured tabs 50/50 against EditorTabs.inactiveColoredFileBackground (#1E1F2280 in
        // expUI_dark); only the SELECTED tab gets the colour at full strength. A muted pastel
        // survives that blend as a handful of RGB points and reads as "no tint at all" — verified
        // during Phase 0, where the original pastels appeared completely absent.
        //
        // Phase 1 replaces these with the Chrome palette, still as JBColor(light, dark) pairs, and
        // must pick each colour against the blend rather than in isolation. Tab TEXT colour is not
        // ours to set, so tints must stay legible under whatever foreground the theme chooses.
        val TEST_TINT = JBColor(Color(0xFF, 0x8A, 0x8A), Color(0x9C, 0x33, 0x33))
        val MARKDOWN_TINT = JBColor(Color(0x8A, 0xB6, 0xFF), Color(0x2E, 0x5C, 0x9C))
    }
}
