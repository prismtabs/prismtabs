package io.chromatabs.phase0

import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
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
 */
internal class Phase0ColorProvider : EditorTabColorProvider {

    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        when (Phase0Rules.tintFor(file.name)) {
            Phase0Rules.Tint.TEST -> TEST_TINT
            Phase0Rules.Tint.MARKDOWN -> MARKDOWN_TINT
            Phase0Rules.Tint.NONE -> null
        }

    private companion object {
        // JBColor(light, dark) so both themes stay legible. Remember: tab TEXT colour
        // is not ours to set, so tints must never fight the default foreground.
        val TEST_TINT = JBColor(Color(0xFF, 0xD6, 0xD6), Color(0x5C, 0x30, 0x30))
        val MARKDOWN_TINT = JBColor(Color(0xD6, 0xE8, 0xFF), Color(0x2B, 0x40, 0x5C))
    }
}
