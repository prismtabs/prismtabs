package io.prismtabs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import io.prismtabs.license.PrismTabsLicense
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabGroupVisibility
import io.prismtabs.service.TabGroupsChangedListener
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Phase 4b: the stable, public-API substitute for Chrome's in-strip group chip (see README
 * "Constraints that are not going away" §3) — every group, live-synced to [TabGroupService] via
 * [TabGroupsChangedListener] rather than only refreshing on its own actions, so a group created
 * from [TabGroupPopup] while this panel is already visible still shows up. `DumbAware`: nothing
 * here reads the index.
 *
 * Phase 6a turned the flat list into a two-level **tree**: each group row expands to one child per
 * member file. A container you expand to see its contents is the platform's own idiom for this
 * question — Bookmarks, Changes, the Project view all answer it that way — rather than an
 * invention, and it is what makes a group's membership knowable once its tabs are closed.
 *
 * This whole tool window — and with it hide/show, which is only driven from here — is a paid
 * feature (see the README's "Licensing and pricing"). It is gated inside [TabGroupsPanel]
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

    /** Invisible: the groups are the top level, and a "Tab Groups" root above them says nothing. */
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        // Named so the uiTests can address this exact tree: the IDE frame holds several others
        // (the Project view's among them), and a by-type locator is a coin toss between them.
        name = GROUP_TREE_NAME
        isRootVisible = false
        showsRootHandles = true
        // Discontiguous, so a Ctrl/Cmd-click can gather files that are nowhere near each other in
        // the tree — which is the ordinary shape of "these four are done with, drop them". The
        // group actions below stay unambiguous by refusing a selection that spans two groups
        // ([selectedGroup]), rather than by keeping the tree single-selection.
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        cellRenderer = TabGroupsTreeRenderer(service)
        emptyText.text = "No tab groups yet — right-click a tab to create one."
        // Double-click means hide/show on a group row and open on a file row (see below), so it must
        // not *also* be the tree's expand toggle: one gesture doing two things at once is how you
        // get a group whose tabs vanish because the user meant to look inside it. Expanding stays on
        // the handle, the arrow keys and Enter, which is where a tree's own affordance lives anyway.
        toggleClickCount = 0
    }
    private val groupTree = ScrollPaneFactory.createScrollPane(tree, true)
    private val toolbarComponent: JComponent

    init {
        // A list, so the two surfaces below can each wrap the *same* action instances: an
        // ActionGroup is not meant to be re-parented, and copying children out of one with
        // `getChildren(null)` is the deprecated route.
        val groupActions = listOf(
            ToggleActiveAction(project, service, ::selectedGroup),
            ToggleVisibilityAction(project, service, ::selectedGroup),
            RenameAction(project, service, ::selectedGroup),
            RecolorAction(project, service, ::selectedGroup),
            DeleteAction(project, service, ::selectedGroup),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("PrismTabs.TabGroupsToolWindow", DefaultActionGroup(groupActions), true)
        toolbar.setTargetComponent(tree)
        toolbarComponent = toolbar.component

        // The context menu is the toolbar's actions *plus* the one thing that acts on a file rather
        // than on a group. It is deliberately not in the toolbar: the toolbar has no row under the
        // cursor to speak of, so a file-only button there would sit disabled for most of the tree's
        // life. Behind a separator for the same reason the menu needs it at all — "Remove from
        // Group" unassigns one file and sits next to "Delete Group", which destroys the group the
        // file is in, and those two must not read as neighbours in the same list.
        val contextMenuActions = DefaultActionGroup(groupActions).apply {
            addSeparator()
            add(RemoveFromGroupAction(project, service, ::selectedMembers))
        }
        installRowSelectingPopupMenu(tree, contextMenuActions)

        // Speed search over the member names as well as the group names, and `canExpand` so it
        // reaches into *collapsed* groups: finding a file whose tab is closed is the whole point of
        // this tree, and a filter that only searched what is already on screen would miss exactly
        // the rows the user came here for.
        TreeSpeedSearch.installOn(tree, true) { path -> searchTextFor(path.lastPathComponent) }

        // Double-click as a shortcut for the toolbar's hide/show toggle — mirrors the platform's own
        // convention of double-click-as-default-action on list/tree tool windows. Read off the path
        // under the cursor rather than the selection: a double-click on empty space below the last
        // row must do nothing, not act on whatever happened to be selected.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                doubleClickOn(tree.getPathForLocation(e.x, e.y) ?: return)
            }
        })
        // Enter, registered as an action rather than left to the tree's own key bindings so both
        // kinds of row have a defined answer.
        DumbAwareAction.create { enterOn(tree.selectionPath ?: return@create) }
            .registerCustomShortcutSet(CommonShortcuts.ENTER, tree, disposable)

        val connection = project.messageBus.connect(disposable)
        connection.subscribe(TabGroupsChangedListener.TOPIC, TabGroupsChangedListener { refresh() })
        // Opening or closing a tab changes no group, but it does change which members are drawn as
        // open — and a row that stayed bold after its tab was closed would be wrong about the one
        // thing this tree exists to report. Rebuilding on an editor event is blunt for what is a
        // per-row boolean, but the alternative is asking FileEditorManager per painted row, which is
        // the service call in a paint path this renderer was deliberately freed of.
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refresh()

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refresh()
            },
        )
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

    /**
     * Rebuilds the whole tree from [TabGroupService], preserving what the user was looking at.
     *
     * The model is replaced wholesale — a change notification says only "something changed", and
     * diffing groups and members against the old nodes to save a rebuild of at most a few dozen rows
     * would be more machinery than the thing it optimises. What that costs is the two pieces of
     * *view* state Swing throws away with the nodes, so both are captured before the swap and put
     * back after: which groups were expanded, and which row was selected. Without the first,
     * expanding a group and then recolouring it would collapse the very thing you were looking at.
     */
    private fun refresh() {
        if (!PrismTabsLicense.paidFeaturesEnabled()) {
            setToolbar(null)
            setContent(upsellPanel())
            // Not just hidden — emptied. A lapsed subscriber's tree must not keep answering
            // `uiDataSnapshot` (or hold group data) from behind the paywall panel.
            root.removeAllChildren()
            treeModel.reload()
            return
        }
        // Only when it isn't already showing: `setContent` removes and re-adds the component, and
        // since this now runs on every editor open and close, doing that unconditionally would put
        // a remove/add of the focused tree in the middle of an ordinary "open a file".
        if (content !== groupTree) {
            setToolbar(toolbarComponent)
            setContent(groupTree)
        }

        val expandedIds = groupNodes().filter { tree.isExpanded(it.treePath) }.mapTo(mutableSetOf()) { it.group.id }
        val selection = selectionKeys()

        root.removeAllChildren()
        service.groups().forEach { root.add(groupNode(it)) }
        treeModel.reload()

        groupNodes().filter { it.group.id in expandedIds }.forEach { tree.expandPath(it.treePath) }
        restoreSelection(selection)
    }

    /** One group row and its member children, in [memberFileRows]' display order. */
    private fun groupNode(group: TabGroup): GroupNode {
        val vfm = VirtualFileManager.getInstance()
        val editors = FileEditorManager.getInstance(project)
        // Membership is resolved once, here, for the same reason the popup's file list does it: the
        // renderer must not do VFS lookups per paint, and a row's icon, its open/closed state and
        // its missing/present state all have to agree with each other.
        val resolved = service.membersOf(group.id).associateWith { vfm.findFileByUrl(it) }
        val node = GroupNode(group)
        memberFileRows(resolved.keys, project.basePath) { resolved[it] == null }.forEach { row ->
            val file = resolved[row.url]
            node.add(
                MemberNode(
                    group = group,
                    row = row,
                    icon = file?.fileType?.icon ?: AllIcons.FileTypes.Unknown,
                    openInEditor = file != null && editors.isFileOpen(file),
                ),
            )
        }
        // Off the children this tree just built, not a second trip through
        // [TabGroupVisibility.isHidden]: the two answer the same question ("is any member open?"),
        // and taking it from the rows means the group row can never contradict the bold ones under
        // it — no member bold, and only then, the word "hidden".
        node.hidden = node.childCount > 0 && node.members.none { it.openInEditor }
        return node
    }

    /**
     * What the toolbar and the context menu act on: **the selected group, or the selected file's
     * parent group**. Without the second half every action would go dead the moment a child row was
     * selected — and selecting a child is the ordinary way to use this tree.
     *
     * With the tree multi-selection, "the selected group" must still be *one* group: a selection
     * spanning two of them answers null, so Rename, Recolour, Delete and the two toggles grey out
     * rather than silently picking whichever row happens to lead. Rows from a single group — one
     * group row, or any number of its files — leave them live, which is the case that matters.
     */
    private fun selectedGroup(): TabGroup? =
        selectedNodes().map { it.group }.distinctBy { it.id }.singleOrNull()

    /**
     * What the context menu's file action acts on: **every selected file**, and only files. The
     * deliberate opposite of [selectedGroup]'s widening — a group row has no one member to remove,
     * so a selection that includes one yields nothing and the action hides itself rather than
     * guessing which of that group's files were meant.
     *
     * Files from *different* groups are allowed, unlike [selectedGroup]'s single-group rule:
     * "unassign these" is well defined however the selection is spread, while "rename this group"
     * is not.
     */
    private fun selectedMembers(): List<MemberNode> {
        val nodes = selectedNodes()
        val members = nodes.filterIsInstance<MemberNode>()
        return if (members.size == nodes.size) members else emptyList()
    }

    private fun selectedNodes(): List<TabGroupsNode> =
        tree.selectionPaths.orEmpty().mapNotNull { it.lastPathComponent as? TabGroupsNode }

    /**
     * Double-click on [path]: **hide or show** a group row, open a file row. The group binding is
     * the one this window already had and Phase 6a kept; what it must not become is the tree's own
     * expand toggle, which is why [tree] is built with `toggleClickCount = 0`.
     */
    private fun doubleClickOn(path: TreePath) {
        when (val node = path.lastPathComponent) {
            is GroupNode -> toggleVisibility(project, service, node.group)
            is MemberNode -> chooseMemberFile(project, node.group, service, node.row)
        }
    }

    /**
     * Enter on [path]: open a file row; **expand or collapse** a group row, which is what Enter on a
     * container means everywhere else.
     *
     * Deliberately not hide/show — that is the double-click's job. Enter is what a keyboard user
     * presses while arrowing down a tree to look inside a row, and closing a group's tabs would be
     * the most surprising thing in the plugin to hang off that.
     *
     * Acts on the **lead row only**, even now that several can be selected: opening a whole
     * selection would queue one missing-file dialog per member that is gone from disk
     * ([chooseMemberFile] asks about each), and a queue of identical modals is the thing
     * [promptToDeleteEmptied] exists to avoid. Removal is the multi-row operation here; opening
     * stays one file at a time.
     */
    private fun enterOn(path: TreePath) {
        when (val node = path.lastPathComponent) {
            is GroupNode -> if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
            is MemberNode -> chooseMemberFile(project, node.group, service, node.row)
        }
    }

    private fun groupNodes(): List<GroupNode> = (0 until root.childCount).map { root.getChildAt(it) as GroupNode }

    /**
     * What was selected, in terms that survive the node objects being thrown away: the group's id,
     * the member's URL if a member was selected, and the group's position so a *deleted* group can
     * fall to whatever took its place.
     */
    private data class SelectionKey(val groupId: String, val url: String?, val groupIndex: Int)

    /**
     * Every selected row, not just the lead one: the tree is multi-selection, and `refresh()` runs
     * on ordinary editor opens and closes too — a gathered selection of six files that collapsed to
     * one row because a tab opened elsewhere would be worse than useless.
     */
    private fun selectionKeys(): List<SelectionKey> =
        selectedNodes().map { node ->
            val groupId = node.group.id
            SelectionKey(groupId, (node as? MemberNode)?.row?.url, groupNodes().indexOfFirst { it.group.id == groupId })
        }

    private fun restoreSelection(keys: List<SelectionKey>) {
        if (keys.isEmpty()) return
        val groups = groupNodes()
        // The same rows that were selected, minus any that are gone: a member is matched by URL
        // within its group and falls back to the group row when it is no longer assigned there
        // (unassigned, renamed onto a new URL, or deleted from disk between refreshes).
        val restored = keys.mapNotNull { key ->
            val group = groups.firstOrNull { it.group.id == key.groupId } ?: return@mapNotNull null
            key.url?.let { url -> group.members.firstOrNull { it.row.url == url } } ?: group
        }
        if (restored.isNotEmpty()) {
            select(restored.distinct())
            return
        }
        // Every selected group is gone — deleted here or from a tab's popup. Fall to the row that
        // took the first one's place (the last row, if it was the last) rather than leaving the tree
        // with no selection: an empty selection disables the whole toolbar and context menu, which
        // is the platform's convention for "nothing is selected" and reads as a dead panel right
        // after a delete. Only when something *was* selected: a tree nobody has touched yet must
        // not select a row on its own.
        if (groups.isNotEmpty()) select(listOf(groups[keys.first().groupIndex.coerceIn(groups.indices)]))
    }

    /** Selects exactly [nodes], scrolling the first back into view. */
    private fun select(nodes: List<DefaultMutableTreeNode>) {
        val paths = nodes.map { it.treePath }
        tree.selectionPaths = paths.toTypedArray()
        paths.firstOrNull()?.let { tree.scrollPathToVisible(it) }
    }

    /**
     * The selected *file* row, published for the platform's own file actions — F4 (Jump to Source),
     * Select in Project View, Copy Path — so this tree gets them without reimplementing any of them.
     *
     * Only file rows publish: a group row has no single file, and answering with all of its
     * members would silently point file-wide actions at a whole group — so a selection that
     * includes one publishes nothing at all ([selectedMembers]). The panel is the provider
     * rather than the tree because [SimpleToolWindowPanel] already implements
     * `UiCompatibleDataProvider`, and the tree is inside it either way.
     */
    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val vfm = VirtualFileManager.getInstance()
        // Re-resolved, not cached on the nodes: the snapshot is read at the moment an action asks,
        // and a file may have gone since the tree was last built.
        val files = selectedMembers().mapNotNull { vfm.findFileByUrl(it.row.url) }
        if (files.isEmpty()) return
        // The array is the whole selection — that is what a multi-file action (Copy Paths, Delete)
        // reads — while the single-file keys answer with the first, since F4 and Select in Project
        // View can only mean one file.
        sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = files.toTypedArray()
        sink[CommonDataKeys.VIRTUAL_FILE] = files.first()
        sink[CommonDataKeys.NAVIGATABLE] = OpenFileDescriptor(project, files.first())
    }

    /**
     * What an unsubscribed user sees here instead of the tree: what this window is, what stays free,
     * and a way to subscribe — the platform's own empty-state idiom, so it reads as a locked feature
     * rather than a failure.
     *
     * The "reopen the tabs" line is not an upsell, it's the escape hatch: hiding a group closes its
     * tabs and only this window can reopen them, so a subscription lapsing while a group is hidden
     * would otherwise hold those tabs hostage behind the paywall. Offered only when there is
     * something to reopen, and it shows the groups rather than re-locking — a lapsed user gets their
     * tabs back, but no ongoing hide/show.
     */
    private fun upsellPanel(): JComponent {
        val panel = UnlockWatchingPanel { if (PrismTabsLicense.paidFeaturesEnabled()) refresh() }
        // Named so the paywall uiTest can find this exact panel: the tool window hierarchy contains
        // other JBPanelWithEmptyText instances belonging to the platform (their empty text reads
        // "Nothing to show"), and a by-type search picks one of those up instead.
        panel.name = LOCKED_PANEL_NAME
        val statusText = panel.emptyText
        statusText.setText("The PrismTabs tool window is a paid feature.")
        // Two lines, because StatusText never wraps: as one line this sentence is wider than a
        // default-width tool window and is clipped at both ends.
        statusText.appendLine("Colouring tabs and managing groups from", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        statusText.appendLine("a tab's right-click menu stays free.", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        statusText.appendLine(
            "Start the free trial or activate a subscription",
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) {
            PrismTabsLicense.requestLicense("The PrismTabs tool window needs an active PrismTabs subscription.")
        }
        val strandedCount = strandedGroups(project, service).size
        if (strandedCount > 0) {
            statusText.appendLine(
                "Reopen the tabs of $strandedCount hidden group${if (strandedCount == 1) "" else "s"}",
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) {
                showAllHiddenGroups(project, service)
            }
        }
        return panel
    }
}

/**
 * A row of the tool window's tree. Both kinds answer [group] — a member row with the group it
 * belongs to — which is what lets the toolbar act on "the selected group, or the selected file's
 * parent group" without caring which row is selected.
 */
private sealed class TabGroupsNode(userObject: Any, allowsChildren: Boolean) :
    DefaultMutableTreeNode(userObject, allowsChildren) {

    abstract val group: TabGroup
}

/**
 * One group row. [hidden] is *derived* — the group has members and none of them is open — and is
 * set once the children are in place rather than read from the model, because there is no stored
 * flag to read: see [TabGroupVisibility] for why. A `var` for that reason alone; nothing mutates it
 * after [TabGroupsPanel.groupNode] returns.
 */
private class GroupNode(override val group: TabGroup) : TabGroupsNode(group, true) {
    var hidden: Boolean = false
    val members: List<MemberNode> get() = (0 until childCount).map { getChildAt(it) as MemberNode }
}

/**
 * One member file. [openInEditor] is read when the tree is built rather than at paint time, for the
 * same reason the icon is: the renderer must stay a pure function of the node.
 */
private class MemberNode(
    override val group: TabGroup,
    val row: MemberFileRow,
    val icon: Icon,
    val openInEditor: Boolean,
) : TabGroupsNode(row, false)

private val DefaultMutableTreeNode.treePath: TreePath get() = TreePath(path)

/** What speed search matches a row on: the group's name, or the file's own name. */
private fun searchTextFor(node: Any?): String = when (node) {
    is GroupNode -> node.group.name
    is MemberNode -> node.row.name
    else -> ""
}

/**
 * Installs [actions] as [tree]'s context menu, moving the selection to the row under the cursor
 * before the menu opens.
 *
 * [PopupHandler.installPopupMenu] on its own is not enough, and the gap is one users actually hit:
 * Swing moves a tree's selection for the *left* mouse button only, so a right-click on an unselected
 * row leaves the selection wherever it was — and every action here is enabled by the selected group
 * (see [SelectionAction]), so a tree with nothing selected shows a wholly greyed-out menu. Deleting
 * the selected group is the everyday way into that state, which is what made this read as a focus
 * bug: the group vanishes, the selection goes with it, and the next right-click is dead until a
 * left-click revives it. Reproduced end-to-end, and now guarded, by
 * `TabGroupsToolWindowUiTest."right-clicking a group row acts on that row..."`.
 *
 * Selecting the right-clicked row is what the platform's own tool windows do — right-clicking an
 * unselected file in the Project view selects it before opening the menu, verified against a live
 * sandbox IDE rather than assumed. A click *inside* an existing multi-selection leaves it whole,
 * for the same reason and from the same source: otherwise the menu's own multi-file item could
 * never be reached, since opening the menu would have just discarded the selection it acts on. The
 * selection-aware helper the platform ships for trees
 * ([PopupHandler.installFollowingSelectionTreePopup]) gets only half of that: it *suppresses* the
 * menu entirely unless the click is already on the selection, which leaves the greyed-out-menu
 * gap above wide open.
 *
 * `getPathForLocation` returns null for a point that isn't on a row, which is the bounds check the
 * list this replaced had to do by hand: a right-click on the empty space below the last row leaves
 * the selection alone instead of snapping to the nearest row.
 */
private fun installRowSelectingPopupMenu(tree: JTree, actions: ActionGroup) {
    tree.addMouseListener(object : PopupHandler() {
        override fun invokePopup(comp: Component, x: Int, y: Int) {
            // Only when the click is *outside* the current selection: right-clicking one of several
            // already-selected rows must keep all of them, or a multi-file action could never be
            // invoked from the menu it lives in. This is the platform's own rule — verified against
            // the Project view in a live sandbox — and the reason the fix below is a manual
            // selection move rather than `installFollowingSelectionTreePopup`.
            val path = tree.getPathForLocation(x, y)
            if (path != null && tree.selectionPaths?.contains(path) != true) tree.selectionPath = path
            val menu = ActionManager.getInstance().createActionPopupMenu(POPUP_PLACE, actions)
            menu.setTargetComponent(tree)
            menu.component.show(comp, x, y)
        }
    })
}

/** `place` for the context menu built in [installRowSelectingPopupMenu]. */
private const val POPUP_PLACE = "PrismTabs.TabGroupsToolWindow.Popup"

/** Must match `plugin.xml`'s `<toolWindow id="">`. */
private const val TOOL_WINDOW_ID = "PrismTabs"

/**
 * The locked panel, re-checking the licence whenever a window closes or its own window regains focus.
 *
 * Starting the trial or entering a key happens in the IDE's registration dialog, and nothing
 * broadcasts the licence change (see [PrismTabsLicense.requestLicense]). That dialog closing is the
 * moment to unlock, without the user having to close and reopen the tool window. It is caught as
 * the dialog's own `WINDOW_CLOSED` rather than as focus returning to the frame, because handing
 * focus back is up to the window manager, and some (or none, under Xvfb) leave it nowhere. Focus
 * regained still counts, for a subscription bought in the browser on the user's return to the IDE.
 *
 * The listener is application-wide, so it is installed only while this panel is on screen.
 */
private class UnlockWatchingPanel(private val onLicenceMayHaveChanged: () -> Unit) : JBPanelWithEmptyText() {

    private val windowListener = AWTEventListener { event ->
        val closed = event.id == WindowEvent.WINDOW_CLOSED
        val refocused = event.id == WindowEvent.WINDOW_GAINED_FOCUS && event.source === SwingUtilities.getWindowAncestor(this)
        if (closed || refocused) onLicenceMayHaveChanged()
    }

    override fun addNotify() {
        super.addNotify()
        Toolkit.getDefaultToolkit().addAWTEventListener(windowListener, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK)
    }

    override fun removeNotify() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(windowListener)
        super.removeNotify()
    }
}

/**
 * `Component.name` of the locked (unsubscribed) tool window panel. The paywall uiTest looks the
 * panel up by this exact name — searching by type finds the platform's own `JBPanelWithEmptyText`
 * instances in the same hierarchy instead. Kept in step with the literal in
 * `IdeaFrame.toolWindowEmptyText`.
 */
internal const val LOCKED_PANEL_NAME = "PrismTabs.LockedToolWindow"

/**
 * `Component.name` of the tool window's group tree. The uiTests locate it by this exact name for the
 * same reason they do the locked panel — kept in step with the literal in `IdeaFrame`'s
 * `GROUP_TREE_XPATH`.
 */
internal const val GROUP_TREE_NAME = "PrismTabs.GroupTree"

/**
 * The groups the paywall would strand: hidden, with tabs recorded that only this window can reopen.
 *
 * Both halves are needed. Hidden alone would count a group whose files the user has simply never
 * opened, offering to "reopen" tabs that were never there; a recorded set alone would count every
 * group in the project, since the memory is kept current for all of them and outlives a show.
 */
private fun strandedGroups(project: Project, service: TabGroupService): List<TabGroup> =
    service.groups().filter { it.rememberedTabUrls.isNotEmpty() && TabGroupVisibility.isHidden(project, service, it) }

/**
 * The locked tool window's escape hatch: reopen the tabs of every hidden group. Deliberately *not*
 * gated — see [TabGroupsPanel.upsellPanel] for why a lapsed subscription must never leave tabs
 * closed behind the paywall that closed them.
 *
 * `internal`, not `private`: the paywall uiTest drives this directly rather than clicking a
 * `StatusText` link at computed coordinates, the same trade-off (and for the same reason)
 * `IdeaFrame.setGroupHidden` already makes for hide/show.
 *
 * The list is resolved once before iterating rather than re-tested per iteration: showing one group
 * reopens tabs, which changes nothing about whether the *others* are hidden, but it does republish a
 * change — and re-reading mid-loop would make the result depend on notification timing for no gain.
 */
internal fun showAllHiddenGroups(project: Project, service: TabGroupService) {
    strandedGroups(project, service).forEach { TabGroupVisibility.show(project, service, it) }
}

/**
 * Group rows — swatch dot, name, member count, "hidden" and "active" suffixes — and file rows,
 * which are the popup's own member rows ([appendMemberFile]) so the two surfaces cannot drift.
 *
 * A member whose tab is currently **open** is drawn bold. The two rows have to be told apart —
 * closed members are the whole reason this tree exists — and bold is the emphasis the platform
 * already spends on "this one is live"; greying the closed ones, the only other obvious reading,
 * would say *disabled* about exactly the rows the user came here to find. Checked against the
 * rendered tree in a sandbox IDE, not picked off a palette.
 */
private class TabGroupsTreeRenderer(private val service: TabGroupService) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (value) {
            is GroupNode -> {
                val group = value.group
                icon = groupSwatch(group)
                append(group.name)
                // "files", not "tabs": a group's members are files, and a group whose every tab is
                // closed still has all of them (see TabGroupService's doc). Calling them tabs made
                // the row read as a lie exactly when it mattered most — a group with three members
                // and nothing open announcing "3 tabs" beside an empty strip. Counted off the
                // children rather than re-asking the service, which is the same number by
                // construction and keeps the paint path free of service calls.
                val count = value.childCount
                append("  $count ${if (count == 1) "file" else "files"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Derived, and so always true of the strip right now: reopening one member from the
                // tree drops the word on that same refresh, and closing the last tab of a group puts
                // it back. See [TabGroupVisibility].
                if (value.hidden) append("  ·  hidden", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Same suffix wording the tab popup's group list uses, so the views name the state alike.
                if (group.id == service.activeGroupId()) {
                    append("  ·  active", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is MemberNode -> {
                icon = value.icon
                appendMemberFile(
                    value.row,
                    if (value.openInEditor) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    },
                )
            }
        }
    }
}

/**
 * Every action here reads the tree's live Swing selection directly in [update] rather than through
 * `DataContext`, so — per the plan's own rule ("return BGT unless you touch Swing") — these need
 * [ActionUpdateThread.EDT], not the [io.prismtabs.action.AssignTabGroupAction] default.
 *
 * [selectedGroup] is a supplier, not the tree itself: since Phase 6a the answer is "the selected
 * group, or the selected file's parent group", and that resolution belongs to the panel that owns
 * the node types rather than being repeated in five actions.
 */
private abstract class SelectionAction(
    text: String,
    icon: Icon,
    protected val project: Project,
    protected val service: TabGroupService,
    protected val selectedGroup: () -> TabGroup?,
) : AnAction(text, null, icon) {

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectedGroup() != null
    }
}

/**
 * Marking the selected group active — the same toggle the tab popup's "Mark as Active Group" item
 * drives, brought here because the tool window is where a subscriber manages groups and having to
 * go back to a tab's right-click menu for this one operation was the gap.
 *
 * A [ToggleAction] rather than a text-swapping [AnAction] like [ToggleCollapseAction]: "active" is
 * a state one group is in at a time, and the platform renders a toggle as a checked item in the
 * context menu and a pressed button in the toolbar — both of which say "this group is the active
 * one" while the menu is open, which a Mark/Clear label pair cannot. The gate lives in
 * [toggleActiveGroup], so clearing stays free for a lapsed subscriber here too.
 */
private class ToggleActiveAction(
    private val project: Project,
    private val service: TabGroupService,
    private val selectedGroup: () -> TabGroup?,
) : ToggleAction("Active Group", "Newly opened files join this group", AllIcons.General.Pin_tab) {

    // Same EDT/selection contract as [SelectionAction], repeated rather than inherited: a
    // ToggleAction cannot also extend it, and the two lines are cheaper than a shared interface.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = selectedGroup() != null
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val selected = selectedGroup() ?: return false
        return selected.id == service.activeGroupId()
    }

    // `state` is ignored on purpose: it is always the negation of [isSelected], which is exactly
    // what toggleActiveGroup recomputes — and going through that one helper is what keeps the
    // licence gate (and the change notification) identical to the tab popup's route.
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val group = selectedGroup() ?: return
        toggleActiveGroup(project, group, service)
    }
}

/**
 * Hide/Show, never Collapse/Expand: the action closes a group's tabs and reopens them, with nothing
 * left behind in their place, so the eye pair is what users read it as — and *expanding a tree row*,
 * a gesture this very tree also has, shows a group's files and touches no tabs.
 *
 * Which of the two it offers comes from [TabGroupVisibility.isHidden], so a group whose tabs are all
 * back on the strip offers Hide even if they were reopened one by one from the tree. Reading a
 * stored flag here was what made showing such a group a no-op that cleared a word and did nothing
 * else.
 */
private class ToggleVisibilityAction(project: Project, service: TabGroupService, selectedGroup: () -> TabGroup?) :
    SelectionAction("Hide Group", PrismTabsIcons.Hide, project, service, selectedGroup) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        val group = selectedGroup()
        // Disabled for a group with no files: there is nothing to close and nothing to reopen, so
        // both halves of the toggle would be no-ops. The stored flag used to let one through, and an
        // empty group could sit there reading "0 files · hidden".
        e.presentation.isEnabled = group != null && service.membersOf(group.id).isNotEmpty()
        val hidden = group != null && TabGroupVisibility.isHidden(project, service, group)
        e.presentation.text = if (hidden) "Show Group" else "Hide Group"
        e.presentation.icon = if (hidden) PrismTabsIcons.Show else PrismTabsIcons.Hide
    }

    override fun actionPerformed(e: AnActionEvent) {
        val group = selectedGroup() ?: return
        toggleVisibility(project, service, group)
    }
}

private class RenameAction(project: Project, service: TabGroupService, selectedGroup: () -> TabGroup?) :
    SelectionAction("Rename…", AllIcons.Actions.Edit, project, service, selectedGroup) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = selectedGroup() ?: return
        promptForRenameAndApply(project, group, service)
    }
}

private class RecolorAction(project: Project, service: TabGroupService, selectedGroup: () -> TabGroup?) :
    SelectionAction("Recolour…", AllIcons.Actions.Colors, project, service, selectedGroup) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = selectedGroup() ?: return
        JBPopupFactory.getInstance().createListPopup(RecolorStep(project, group, service)).showInBestPositionFor(e.dataContext)
    }
}

/**
 * `Actions.GC` despite the name: it is the platform's own waste-basket, drawn as one in both the
 * classic and the new UI (`expui/general/delete.svg`). `General.Remove`, which this used to carry,
 * is a minus sign — the icon for taking a row out of a list, not for destroying what it points at.
 */
private class DeleteAction(project: Project, service: TabGroupService, selectedGroup: () -> TabGroup?) :
    SelectionAction("Delete Group", AllIcons.Actions.GC, project, service, selectedGroup) {

    override fun actionPerformed(e: AnActionEvent) {
        val group = selectedGroup() ?: return
        confirmAndDelete(project, group, service)
    }
}

/**
 * Unassigning the selected files — the same operation a tab's own right-click menu offers, brought
 * here because the tool window is the only surface that shows a member whose tab is *closed*, and
 * so the only one where the file to unassign may not have a tab to right-click at all. Without it,
 * dropping a closed file from a group meant opening it first, just to close it again afterwards.
 *
 * `General.Remove`, the minus sign: taking a row out of a list, as opposed to [DeleteAction]'s
 * waste-basket, which destroys what it points at. The pair sits in one menu, so the icons carry
 * the distinction as much as the wording does.
 *
 * Hidden, not merely disabled, on a group row — and on any selection that *includes* one: the
 * platform's own preference is to disable rather than hide, but that reads a file-only verb onto a
 * row that has no file, and the menu already shows five items that *do* apply there. Nothing is
 * lost — the rows the user right-clicked are the rows the menu describes.
 *
 * Multi-selection is the whole point of the tree being discontiguous: the files worth dropping
 * together are rarely adjacent. The selected files may span groups — [removeFromGroups] unassigns
 * each from wherever it is, and the label says so.
 *
 * No confirmation, matching the tab popup: the assignment is one click to restore. Emptying the
 * group is the case that does ask, in [promptToDeleteEmptied], which [removeFromGroup] reaches for
 * both surfaces alike.
 */
private class RemoveFromGroupAction(
    private val project: Project,
    private val service: TabGroupService,
    private val selectedMembers: () -> List<MemberNode>,
) : AnAction("Remove from Group", "Unassign the selected files from their tab groups", AllIcons.General.Remove) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val members = selectedMembers()
        e.presentation.isEnabledAndVisible = members.isNotEmpty()
        if (members.isEmpty()) return
        e.presentation.text = removeFromGroupText(members.size, members.distinctBy { it.group.id }.size)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val urls = selectedMembers().map { it.row.url }
        if (urls.isEmpty()) return
        removeFromGroups(project, service, urls)
    }
}

/**
 * [RemoveFromGroupAction]'s label for a selection of [fileCount] files spread over [groupCount]
 * groups. Pure, and unit-tested in `RemoveFromGroupTextTest`, for the same reason
 * `addToActiveGroupLabel` and `deleteConfirmationMessage` are: the wording is the part that goes
 * wrong, and it needs no platform to check.
 *
 * One file keeps the tab popup's exact words, since it is the same operation seen from the other
 * surface — see [naming and terminology](../../../../../../docs/naming-and-terminology.md) §3. A
 * multi-selection states the count, which is what the platform's own multi-row actions do
 * ("Delete 3 Files"), and pluralises the *group* half honestly: unassigning four files that came
 * from three different groups is not "from Group".
 */
internal fun removeFromGroupText(fileCount: Int, groupCount: Int): String = when {
    fileCount <= 1 -> "Remove from Group"
    groupCount <= 1 -> "Remove $fileCount Files from Group"
    else -> "Remove $fileCount Files from Their Groups"
}

/**
 * The single place hide/show is driven from — the toolbar action, the context menu and the
 * double-click shortcut all land here — so it carries the paid-feature gate itself rather than
 * relying on the locked panel simply not showing the tree. The one deliberate way past it is the
 * locked panel's own "reopen the tabs" escape hatch, which calls [TabGroupVisibility.show] direct.
 */
private fun toggleVisibility(project: Project, service: TabGroupService, group: TabGroup) {
    if (!PrismTabsLicense.requirePaidFeature("Hiding and showing tab groups")) return
    if (TabGroupVisibility.isHidden(project, service, group)) {
        TabGroupVisibility.show(project, service, group)
    } else {
        TabGroupVisibility.hide(project, service, group)
    }
}
