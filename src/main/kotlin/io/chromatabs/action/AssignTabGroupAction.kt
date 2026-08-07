package io.chromatabs.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import io.chromatabs.model.TabGroup
import io.chromatabs.service.TabGroupService
import io.chromatabs.service.TabRepaint

/**
 * PHASE 1 SCAFFOLDING — exercises TabGroupService/TabGroupColorProvider end-to-end while Phase 2
 * builds the real swatch popup. Two hard-coded groups, no rename/recolour/create-new-group UI.
 * Delete this file once TabGroupPopup (Phase 2) replaces it.
 *
 * Colours are the saturated dark-theme variants proven visible on unselected tabs in Phase 0
 * (see README's measured 50%-blend constraint) — this is throwaway, not the Chrome palette.
 */
private val RED_GROUP = TabGroup(id = "phase1-red", name = "Red", colorRgb = 0x9C3333)
private val BLUE_GROUP = TabGroup(id = "phase1-blue", name = "Blue", colorRgb = 0x2E5C9C)

internal abstract class AssignTabGroupActionBase(private val group: TabGroup?) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = project.service<TabGroupService>()
        if (group != null) service.assign(file.url, group) else service.clear(file.url)
        TabRepaint.request(project, file)
    }
}

internal class AssignRedTabGroupAction : AssignTabGroupActionBase(RED_GROUP)
internal class AssignBlueTabGroupAction : AssignTabGroupActionBase(BLUE_GROUP)
internal class ClearTabGroupAction : AssignTabGroupActionBase(null)
