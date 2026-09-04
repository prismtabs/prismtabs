package io.prismtabs.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import io.prismtabs.service.TabGroupService
import io.prismtabs.ui.addToActiveGroup
import io.prismtabs.ui.addToActiveGroupLabel
import io.prismtabs.ui.filesToAdd
import io.prismtabs.ui.groupSwatch

/**
 * "Add to Tab Group \"Auth\"" on the Project view's context menu, and in the keymap.
 *
 * The second surface of the active-group shortcut, and the one that answers the gesture that
 * exposed the gap in the first place: a double-click on a file that is *already open* joins no
 * group, because the platform fires no `fileOpened` for it — see [addToActiveGroupLabel] for the
 * whole reasoning, and the popup's own row for the same operation on an editor tab.
 *
 * It is offered here rather than only on the tab strip because the Project view is where that
 * double-click happens: the file the user means is already selected under the cursor, so the
 * shortcut costs one right-click rather than a trip to a tab that may not even be visible. It
 * takes a multi-selection as it finds it — the Project view's normal way of working — and
 * [filesToAdd] decides what that actually means.
 *
 * **Not licence-gated**, unlike auto-join itself. Marking a group active is the paid feature and
 * stays gated in `toggleActiveGroup`; assigning a file to a group by hand is free forever (see the
 * README's free/paid split), and this is only that, addressed at a group the user already chose.
 * Nor could a lapsed subscriber reach it by accident: `activeGroupId` is session-scoped and never
 * persisted, so an active group can only exist if it was marked in *this* session, through the
 * gate.
 *
 * No default shortcut is bound. The keymap entry is the point — a default keystroke on a plugin
 * action is a conflict with whatever the user already has on it.
 */
internal class AddToActiveGroupAction : AnAction(), DumbAware {

    // Nothing here touches Swing: the presentation is decided from the service's two map lookups
    // and the data context's files, which is exactly the work BGT exists for.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * Hidden outright when no group is active — the shortcut has no destination then, and a
     * permanently greyed row would be noise in a menu this crowded for everyone who never marks a
     * group active at all.
     *
     * Merely *disabled* when a group is active but every selected file is already in a group: the
     * item belongs in this context, it just has nothing to do here, which is what disabled means.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.service<TabGroupService>()
        val active = service?.activeGroup()
        if (active == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = filesToAdd(selectedFiles(e), service).isNotEmpty()
        // setText(_, false), not the `text` property: the one-argument setter passes
        // mayContainMnemonic = true (checked in the bytecode), and a group name is free text. A
        // group called "auth_flow" would otherwise reach the menu as "authflow" with F underlined,
        // and one called "R&D" as "RD" — the name mangled, and a mnemonic invented from it.
        e.presentation.setText(addToActiveGroupLabel(active.name), false)
        e.presentation.icon = groupSwatch(active)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<TabGroupService>()
        val active = service.activeGroup() ?: return
        addToActiveGroup(project, selectedFiles(e), active, service)
    }

    /**
     * What the context is pointing at: the Project view's whole selection when there is one, and
     * the single file otherwise.
     *
     * `VIRTUAL_FILE_ARRAY` first because a multi-selection is ordinary in a tree and taking only
     * its lead file would silently drop the rest. In an editor split the array can hold both
     * visible files rather than just the focused one; both are on screen, both are named by the
     * command the user just invoked, and the tint says immediately which files moved, so that is
     * accepted rather than guessed around.
     */
    private fun selectedFiles(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
}
