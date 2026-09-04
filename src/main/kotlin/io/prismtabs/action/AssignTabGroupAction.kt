package io.prismtabs.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import io.prismtabs.ui.TabGroupPopup

/**
 * The single entry point on `EditorTabPopupMenu`: opens [TabGroupPopup]. Replaces Phase 1's
 * hard-coded Red/Blue/Clear actions now that the real swatch popup exists.
 *
 * [DumbAware] for the same reason `TabGroupColorProvider` is (see README §"The provider must
 * implement `DumbAware`"): a group assignment is an in-memory map keyed by file URL and needs no
 * index, so there is nothing for this to wait on while the IDE reindexes — and a tab that cannot be
 * coloured for the first minute of every project open would be a puzzling thing to ship.
 */
internal class AssignTabGroupAction : AnAction(), DumbAware {

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
