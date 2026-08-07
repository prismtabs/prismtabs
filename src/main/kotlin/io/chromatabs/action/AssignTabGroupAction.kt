package io.chromatabs.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import io.chromatabs.ui.TabGroupPopup

/**
 * The single entry point on `EditorTabPopupMenu`: opens [TabGroupPopup]. Replaces Phase 1's
 * hard-coded Red/Blue/Clear actions now that the real swatch popup exists.
 */
internal class AssignTabGroupAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        TabGroupPopup.show(project, file, e.dataContext)
    }
}
