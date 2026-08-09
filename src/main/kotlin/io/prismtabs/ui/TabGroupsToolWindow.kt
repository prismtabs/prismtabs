package io.prismtabs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupCollapse
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabGroupsChangedListener
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Phase 4b: the stable, public-API substitute for Chrome's in-strip group chip (see README
 * "Constraints that are not going away" §3) — a plain list of every group, live-synced to
 * [TabGroupService] via [TabGroupsChangedListener] rather than only refreshing on its own actions,
 * so a group created from [TabGroupPopup] while this panel is already visible still shows up.
 * `DumbAware`: nothing here reads the index.
 */
internal class TabGroupsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TabGroupsPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class TabGroupsPanel(
    private val project: Project,
    disposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val service = project.service<TabGroupService>()
    private val listModel = CollectionListModel<TabGroup>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = GroupCellRenderer(service)
        emptyText.text = "No tab groups yet — right-click a tab to create one."
    }

    init {
        val actions = DefaultActionGroup(
            ToggleCollapseAction(project, service, list),
            RenameAction(project, service, list),
            RecolorAction(project, service, list),
            DeleteAction(project, service, list),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("PrismTabs.TabGroupsToolWindow", actions, true)
        toolbar.setTargetComponent(list)
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(list, true))
        PopupHandler.installPopupMenu(list, actions, "PrismTabs.TabGroupsToolWindow.Popup")

        // Double-click as a shortcut for the toolbar's collapse/expand toggle — mirrors the
        // platform's own convention of double-click-as-default-action on list/tree tool windows.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val group = list.selectedValue ?: return
                toggleCollapse(project, service, group)
            }
        })

        project.messageBus.connect(disposable).subscribe(TabGroupsChangedListener.TOPIC, TabGroupsChangedListener { refresh() })
        refresh()
    }

    private fun refresh() {
        val groups = service.groups()
        val selectedId = list.selectedValue?.id
        listModel.replaceAll(groups)
        groups.firstOrNull { it.id == selectedId }?.let { list.setSelectedValue(it, true) }
    }
}

/** Swatch dot, name, member count, and a "collapsed" suffix — the plan's stated row shape. */
private class GroupCellRenderer(private val service: TabGroupService) : ColoredListCellRenderer<TabGroup>() {

    override fun customizeCellRenderer(
        list: JList<out TabGroup>,
        value: TabGroup,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = ColorSwatchIcon(JBColor(Color(value.lightRgb), Color(value.darkRgb)))
        append(value.name)
        val count = service.membersOf(value.id).size
        append("  $count ${if (count == 1) "tab" else "tabs"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (value.collapsed) append("  ·  collapsed", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

/**
 * Every action here reads [list]'s live Swing selection directly in [update] rather than through
 * `DataContext`, so — per the plan's own rule ("return BGT unless you touch Swing") — these need
 * [ActionUpdateThread.EDT], not the [io.prismtabs.action.AssignTabGroupAction] default.
 */
private abstract class SelectionAction(
    text: String,
    icon: javax.swing.Icon,
    protected val project: Project,
    protected val service: TabGroupService,
    protected val list: JBList<TabGroup>,
) : AnAction(text, null, icon) {

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = list.selectedValue != null
    }
}

private class ToggleCollapseAction(project: Project, service: TabGroupService, list: JBList<TabGroup>) :
    SelectionAction("Collapse Group", AllIcons.Actions.Collapseall, project, service, list) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        val group = list.selectedValue
        e.presentation.text = if (group?.collapsed == true) "Expand Group" else "Collapse Group"
        e.presentation.icon = if (group?.collapsed == true) AllIcons.Actions.Expandall else AllIcons.Actions.Collapseall
    }

    override fun actionPerformed(e: AnActionEvent) {
        val group = list.selectedValue ?: return
        toggleCollapse(project, service, group)
    }
}

private class RenameAction(project: Project, service: TabGroupService, list: JBList<TabGroup>) :
    SelectionAction("Rename…", AllIcons.Actions.Edit, project, service, list) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = list.selectedValue ?: return
        promptForRenameAndApply(project, group, service)
    }
}

private class RecolorAction(project: Project, service: TabGroupService, list: JBList<TabGroup>) :
    SelectionAction("Recolour…", AllIcons.Actions.Colors, project, service, list) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = list.selectedValue ?: return
        JBPopupFactory.getInstance().createListPopup(RecolorStep(project, group, service)).showInBestPositionFor(e.dataContext)
    }
}

private class DeleteAction(project: Project, service: TabGroupService, list: JBList<TabGroup>) :
    SelectionAction("Delete Group", AllIcons.General.Remove, project, service, list) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = list.selectedValue ?: return
        confirmAndDelete(project, group, service)
    }
}

private fun toggleCollapse(project: Project, service: TabGroupService, group: TabGroup) {
    if (group.collapsed) {
        TabGroupCollapse.expand(project, service, group)
    } else {
        TabGroupCollapse.collapse(project, service, group)
    }
}
