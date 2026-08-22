package io.prismtabs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
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
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory
import io.prismtabs.license.PrismTabsLicense
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupCollapse
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabGroupsChangedListener
import java.awt.Color
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Phase 4b: the stable, public-API substitute for Chrome's in-strip group chip (see README
 * "Constraints that are not going away" §3) — a plain list of every group, live-synced to
 * [TabGroupService] via [TabGroupsChangedListener] rather than only refreshing on its own actions,
 * so a group created from [TabGroupPopup] while this panel is already visible still shows up.
 * `DumbAware`: nothing here reads the index.
 *
 * This whole tool window — and with it collapse/expand, which is only driven from here — is a paid
 * feature (see the README's "Marketplace publishing prep"). It is gated inside [TabGroupsPanel]
 * rather than by declining to register the tool window at all: a subscription can be activated
 * while the IDE is running, and a stripe button that only appears after a restart would read as the
 * plugin being broken.
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
    private val groupList = ScrollPaneFactory.createScrollPane(list, true)
    private val toolbarComponent: JComponent

    init {
        val actions = DefaultActionGroup(
            ToggleCollapseAction(project, service, list),
            RenameAction(project, service, list),
            RecolorAction(project, service, list),
            DeleteAction(project, service, list),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("PrismTabs.TabGroupsToolWindow", actions, true)
        toolbar.setTargetComponent(list)
        toolbarComponent = toolbar.component
        installRowSelectingPopupMenu(list, actions)

        // Double-click as a shortcut for the toolbar's hide/show toggle — mirrors the
        // platform's own convention of double-click-as-default-action on list/tree tool windows.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val group = list.selectedValue ?: return
                toggleCollapse(project, service, group)
            }
        })

        val connection = project.messageBus.connect(disposable)
        connection.subscribe(TabGroupsChangedListener.TOPIC, TabGroupsChangedListener { refresh() })
        // A subscription activated mid-session doesn't notify anyone, so re-read the licence each
        // time the tool window is shown: that's the moment the user would look for the unlock, and
        // it's rare enough that re-verifying the signed stamp costs nothing.
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == TOOL_WINDOW_ID) refresh()
                }
            },
        )
        refresh()
    }

    private fun refresh() {
        if (!PrismTabsLicense.paidFeaturesEnabled()) {
            setToolbar(null)
            setContent(upsellPanel())
            return
        }
        setToolbar(toolbarComponent)
        setContent(groupList)
        val groups = service.groups()
        val selectedIndex = list.selectedIndex
        val selectedId = list.selectedValue?.id
        listModel.replaceAll(groups)
        val stillListed = groups.indexOfFirst { it.id == selectedId }
        when {
            stillListed >= 0 -> select(stillListed)
            // The selected group is gone — deleted here or from a tab's popup. Fall to the row that
            // took its place (the last one, if it was the last) rather than leaving the list with no
            // selection: an empty selection disables the whole toolbar and context menu, which is
            // the platform's convention for "nothing is selected" and reads as a dead panel right
            // after a delete. Only when something *was* selected: a list nobody has touched yet
            // must not select a row on its own.
            selectedId != null && groups.isNotEmpty() -> select(selectedIndex.coerceIn(groups.indices))
        }
    }

    private fun select(index: Int) {
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    /**
     * What an unsubscribed user sees here instead of the list: what this window is, what stays free,
     * and a way to subscribe — the platform's own empty-state idiom, so it reads as a locked feature
     * rather than a failure.
     *
     * The "expand everything" line is not an upsell, it's the escape hatch: collapsing closes tabs
     * and only this window can reopen them, so a subscription lapsing while a group is collapsed
     * would otherwise hold those tabs hostage behind the paywall. Offered only when something is
     * actually collapsed, and it expands rather than re-locking — a lapsed user gets their tabs
     * back, but no ongoing collapse/expand.
     */
    private fun upsellPanel(): JComponent {
        val panel = JBPanelWithEmptyText()
        // Named so the paywall uiTest can find this exact panel: the tool window hierarchy contains
        // other JBPanelWithEmptyText instances belonging to the platform (their empty text reads
        // "Nothing to show"), and a by-type search picks one of those up instead.
        panel.name = LOCKED_PANEL_NAME
        val statusText = panel.emptyText
        statusText.setText("The Tab Groups tool window is a PrismTabs paid feature.")
        statusText.appendLine(
            "Colouring tabs and managing groups from a tab's right-click menu stays free.",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            null,
        )
        statusText.appendLine(
            "Start the free trial or activate a subscription",
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) {
            PrismTabsLicense.requestLicense("The Tab Groups tool window needs an active PrismTabs subscription.")
        }
        val collapsedCount = service.groups().count { it.collapsed }
        if (collapsedCount > 0) {
            statusText.appendLine(
                "Reopen the tabs of $collapsedCount hidden group${if (collapsedCount == 1) "" else "s"}",
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) {
                expandAllCollapsedGroups(project, service)
            }
        }
        return panel
    }
}

/**
 * Installs [actions] as [list]'s context menu, moving the selection to the row under the cursor
 * before the menu opens.
 *
 * [PopupHandler.installPopupMenu] on its own is not enough, and the gap is one users actually hit:
 * Swing's `BasicListUI` moves a list's selection for the *left* mouse button only, so a right-click
 * on an unselected row leaves the selection wherever it was — and every action here is enabled by
 * `list.selectedValue` (see [SelectionAction]), so a list with nothing selected shows a wholly
 * greyed-out menu. Deleting the selected group is the everyday way into that state, which is what
 * made this read as a focus bug: the group vanishes, the selection goes with it, and the next
 * right-click is dead until a left-click revives it. Reproduced end-to-end, and now guarded, by
 * `TabGroupsToolWindowUiTest."right-clicking a group row acts on that row..."`.
 *
 * Selecting the right-clicked row is what the platform's own tool windows do — right-clicking an
 * unselected file in the Project view selects it before opening the menu, verified against a live
 * sandbox IDE rather than assumed. The two selection-aware helpers the platform ships
 * ([PopupHandler.installSelectionListPopup] and `installFollowingSelectionTreePopup`) make the
 * opposite trade: they *suppress* the menu unless the click is already on the selection, which
 * suits a multi-selection list and would only make this one deader still.
 */
private fun installRowSelectingPopupMenu(list: JBList<TabGroup>, actions: ActionGroup) {
    list.addMouseListener(object : PopupHandler() {
        override fun invokePopup(comp: Component, x: Int, y: Int) {
            val index = list.locationToIndex(Point(x, y))
            // locationToIndex snaps to the nearest row for a point past the last one, so without the
            // bounds check a right-click on the empty space below the list would select its last row.
            if (index >= 0 && list.getCellBounds(index, index)?.contains(x, y) == true) {
                list.selectedIndex = index
            }
            val menu = ActionManager.getInstance().createActionPopupMenu(POPUP_PLACE, actions)
            menu.setTargetComponent(list)
            menu.component.show(comp, x, y)
        }
    })
}

/** `place` for the context menu built in [installRowSelectingPopupMenu]. */
private const val POPUP_PLACE = "PrismTabs.TabGroupsToolWindow.Popup"

/** Must match `plugin.xml`'s `<toolWindow id="">`. */
private const val TOOL_WINDOW_ID = "Tab Groups"

/**
 * `Component.name` of the locked (unsubscribed) tool window panel. The paywall uiTest looks the
 * panel up by this exact name — searching by type finds the platform's own `JBPanelWithEmptyText`
 * instances in the same hierarchy instead. Kept in step with the literal in
 * `IdeaFrame.toolWindowEmptyText`.
 */
internal const val LOCKED_PANEL_NAME = "PrismTabs.LockedToolWindow"

/**
 * The locked tool window's escape hatch: reopen the tabs of every collapsed group. Deliberately
 * *not* gated — see [TabGroupsPanel.upsellPanel] for why a lapsed subscription must never leave
 * tabs closed behind the paywall that closed them.
 *
 * `internal`, not `private`: the paywall uiTest drives this directly rather than clicking a
 * `StatusText` link at computed coordinates, the same trade-off (and for the same reason)
 * `IdeaFrame.setGroupCollapsed` already makes for collapse/expand.
 *
 * The group list is read once and filtered before iterating, not re-read per iteration: each
 * [TabGroupCollapse.expand] republishes a change, and `expand` needs the *pre-expand* [TabGroup]
 * (its `collapsedFileUrls` is what says which tabs to reopen — see [TabGroupService.expand]).
 * Expanding one group doesn't affect the others' snapshots, so one read is both correct and the
 * only thing that keeps each group's recorded URLs intact.
 */
internal fun expandAllCollapsedGroups(project: Project, service: TabGroupService) {
    service.groups().filter { it.collapsed }.forEach { TabGroupCollapse.expand(project, service, it) }
}

/** Swatch dot, name, member count, and a "hidden" suffix — the plan's stated row shape. */
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
        if (value.collapsed) append("  ·  hidden", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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

/**
 * Presented as Hide/Show, not Collapse/Expand: the action closes a group's tabs and reopens them,
 * with nothing left behind in their place, so the eye pair is what users read it as. The model
 * keeps saying `collapsed` — that name is persisted state and is not worth churning for wording.
 */
private class ToggleCollapseAction(project: Project, service: TabGroupService, list: JBList<TabGroup>) :
    SelectionAction("Hide Group", PrismTabsIcons.Hide, project, service, list) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        val collapsed = list.selectedValue?.collapsed == true
        e.presentation.text = if (collapsed) "Show Group" else "Hide Group"
        e.presentation.icon = if (collapsed) PrismTabsIcons.Show else PrismTabsIcons.Hide
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

/**
 * `Actions.GC` despite the name: it is the platform's own waste-basket, drawn as one in both the
 * classic and the new UI (`expui/general/delete.svg`). `General.Remove`, which this used to carry,
 * is a minus sign — the icon for taking a row out of a list, not for destroying what it points at.
 */
private class DeleteAction(project: Project, service: TabGroupService, list: JBList<TabGroup>) :
    SelectionAction("Delete Group", AllIcons.Actions.GC, project, service, list) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = list.selectedValue ?: return
        confirmAndDelete(project, group, service)
    }
}

/**
 * The single place collapse/expand is driven from — the toolbar action, the context menu and the
 * double-click shortcut all land here — so it carries the paid-feature gate itself rather than
 * relying on the locked panel simply not showing the list. The one deliberate way past it is the
 * locked panel's own "reopen the tabs" escape hatch, which calls [TabGroupCollapse.expand] direct.
 */
private fun toggleCollapse(project: Project, service: TabGroupService, group: TabGroup) {
    if (!PrismTabsLicense.requirePaidFeature("Hiding and showing tab groups")) return
    if (group.collapsed) {
        TabGroupCollapse.expand(project, service, group)
    } else {
        TabGroupCollapse.collapse(project, service, group)
    }
}
