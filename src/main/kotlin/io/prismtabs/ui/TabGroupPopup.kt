package io.prismtabs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.prismtabs.license.PrismTabsLicense
import io.prismtabs.model.PresetColor
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupNotifier
import io.prismtabs.service.TabGroupPaletteService
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabGroupVisibility
import io.prismtabs.service.TabRepaint
import java.awt.Color
import java.awt.Component
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.border.Border

/**
 * The Chrome-style swatch popup: `AssignTabGroupAction`'s single entry point. Tree shape per the
 * plan's Phase 2 section, with the plan's separate "New Group…" substep folded into the dots
 * themselves — a "New Group" caption over 8 preset dots, a separator, "Add to Group…"
 * (`hasSubstep`), "Manage Groups…" (`hasSubstep`, only once a group exists), and "Remove from
 * Group" under an "In group: <name>" caption — the last two only when the file is currently
 * assigned, and the caption is the only place the popup says *which* group that is.
 */
internal object TabGroupPopup {

    fun show(project: Project, file: VirtualFile, dataContext: DataContext) {
        val service = project.service<TabGroupService>()
        val step = RootStep(project, file, service)
        CaptionedListPopup(project, step).showInBestPositionFor(dataContext)
    }
}

/**
 * A [ListPopupImpl] that paints a separator's caption at the same weight as the list's own items.
 *
 * The platform's `SeparatorWithText` sets its caption to `JBUI.CurrentTheme.Popup.separatorTextColor()`
 * — a deliberately muted grey, right for the incidental dividers it was built for ("Recent Files",
 * "Previous Searches"), where the caption is a hint and the items are the content. [RootStep]'s
 * "New Group" caption is not that: it is the only label the eight swatches ever get, and it names
 * what clicking one *does*. At the platform's grey it reads as chrome and the eye skips it. It is
 * repainted in `UIUtil.getListForeground()` — not an invented colour, but the exact call
 * `GroupedElementsRenderer.List` makes for every item's own text (checked with `javap` per
 * CLAUDE.md's recipe), so the caption tracks the theme wherever the items do. Everything else the
 * platform decides — bold face, the divider line — is left alone.
 *
 * It also gives a *captioned* separator more room beneath it. The platform's own vgap leaves the
 * swatch row crowding the caption — measured on the rendered popup, 3px of clear space below the
 * glyphs, against the 11px a nested step's title gets above its first row. That difference is
 * legible as sloppiness when the two sit one click apart, so [CAPTION_BOTTOM_GAP] closes it and the
 * two headers in this plugin breathe alike.
 *
 * Constructing [ListPopupImpl] directly rather than via `JBPopupFactory.createListPopup` is what
 * makes [getListElementRenderer] reachable: the factory returns this same class, but as a
 * `ListPopup` with no hook for its renderer.
 *
 * Building a fresh renderer per call, rather than caching one in a field, matches the platform's own
 * implementation and is the only constructor-safe option: `createContent()` calls this from within
 * the superclass constructor, before any subclass field initialiser has run.
 */
private class CaptionedListPopup(project: Project, step: ListPopupStep<*>) : ListPopupImpl(project, step) {

    override fun getListElementRenderer(): ListCellRenderer<*> =
        object : PopupListElementRenderer<Any>(this) {

            /**
             * The platform's own separator border, captured before this class first widens it.
             *
             * Needed because `GroupedElementsRenderer` builds **one** separator component and reuses
             * it for every row that has one — so the border cannot simply be widened once at
             * creation without also pushing "Add to Group…" away from the bare divider above it,
             * and it cannot be compounded on each pass without stacking up indefinitely. Captured
             * lazily on first render rather than in [createSeparator]: that runs from the
             * superclass constructor, before this field's initialiser.
             */
            private var platformSeparatorBorder: Border? = null

            // Return type inferred rather than spelled out: `createSeparator` is the live extension
            // point and is not itself deprecated, but the `SeparatorWithText` it is declared to
            // return is (a bare `@Deprecated`, no removal schedule — the platform still returns its
            // non-deprecated `GroupHeaderSeparator` subclass from here). Naming it would import a
            // deprecation this override has no way to avoid and no reason to advertise.
            override fun createSeparator() =
                super.createSeparator().apply { textForeground = UIUtil.getListForeground() }

            override fun getListCellRendererComponent(
                list: JList<out Any>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val rendered = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                // After super, the shared separator carries this row's caption, which is what
                // decides whether it is a heading (widen) or a plain divider (leave alone).
                val separator = mySeparatorComponent
                val platform = platformSeparatorBorder ?: separator.border.also { platformSeparatorBorder = it }
                separator.border = if (separator.caption.isNullOrBlank()) {
                    platform
                } else {
                    BorderFactory.createCompoundBorder(platform, JBUI.Borders.emptyBottom(CAPTION_BOTTOM_GAP))
                }
                return rendered
            }
        }

    private companion object {
        /**
         * Extra space below a separator's caption, in unscaled px — `JBUI.Borders` scales it, so it
         * holds at HiDPI. 8 is not a taste call: it is the measured shortfall between this caption's
         * 3px of clear space and the 11px a nested step's title gets, so the two match rather than
         * merely both being "roomy".
         */
        const val CAPTION_BOTTOM_GAP = 8
    }
}

/**
 * The effective palette: [io.prismtabs.model.TabGroupPalette.PRESETS] with any customisation from
 * [TabGroupPaletteConfigurable] laid over it. `TabGroupPaletteService` is application-level, so this
 * is fetched ambiently rather than threaded through every step's constructor — same footing as
 * `VirtualFileManager.getInstance()` in [repaintFiles] below.
 */
private fun effectivePresets(): List<PresetColor> = service<TabGroupPaletteService>().presets()

/**
 * A group's label in a list step, suffixed with "(active)" when it is the active group.
 *
 * Shared by [AddToGroupStep] and [ManageGroupsStep] so the marker cannot drift between the two
 * places a group is listed by name.
 */
private fun labelFor(group: TabGroup, service: TabGroupService): String =
    if (group.id == service.activeGroupId()) "${group.name} (active)" else group.name

/**
 * A group's own colour as a row icon — the dot [AddToGroupStep], [ManageGroupsStep] and the
 * active-group shortcut all carry, and the [io.prismtabs.action.AddToActiveGroupAction] menu item
 * with them.
 *
 * `internal`, not `private`: that action lives outside this file and must show the *same* dot, or
 * the Project view would label the active group with a colour the popup does not.
 */
internal fun groupSwatch(group: TabGroup): Icon =
    ColorSwatchIcon(JBColor(Color(group.lightRgb), Color(group.darkRgb)))

private sealed class Entry {
    class Preset(val preset: PresetColor) : Entry()

    /**
     * The active group offered as a destination in its own right, rather than one name inside
     * [AddToGroupStep]'s list. See [addToActiveGroupLabel] for why the row exists and
     * [RootStep.buildEntries] for the one state it is offered in.
     */
    class AddToActive(val group: TabGroup) : Entry()

    data object AddToGroup : Entry()
    data object ManageGroups : Entry()
    data object RemoveFromGroup : Entry()
}

private fun textFor(entry: Entry): String = when (entry) {
    is Entry.Preset -> entry.preset.label
    is Entry.AddToActive -> addToActiveGroupLabel(entry.group.name)
    Entry.AddToGroup -> "Add to Group…"
    Entry.ManageGroups -> "Manage Groups…"
    Entry.RemoveFromGroup -> "Remove from Group"
}

private fun iconFor(entry: Entry): Icon? = when (entry) {
    is Entry.Preset -> ColorSwatchIcon(JBColor(entry.preset.light, entry.preset.dark))
    is Entry.AddToActive -> groupSwatch(entry.group)
    else -> null
}

private class RootStep(
    private val project: Project,
    private val file: VirtualFile,
    private val service: TabGroupService,
) : BaseListPopupStep<Entry>("", buildEntries(service, file)) {

    private var finalRunnable: Runnable? = null

    /**
     * The row that opens the block *under* the swatch divider — [Entry.AddToActive] when the
     * active-group shortcut is offered, [Entry.AddToGroup] when it is not.
     *
     * Held rather than named literally in [getSeparatorAbove], so the block keeps exactly one
     * divider however it was built: pinning the divider to "Add to Group…" would leave the
     * shortcut row stranded above it, in the swatch block, where every other row creates a group.
     */
    private val firstBelowSwatches: Entry? = values.firstOrNull { it !is Entry.Preset }

    override fun getTextFor(value: Entry): String = textFor(value)

    override fun getIconFor(value: Entry): Icon? = iconFor(value)

    /**
     * The "New Group" caption over the swatch row is a captioned [ListSeparator] on the *first*
     * entry rather than the step's own title: the popup's title bar would label the whole tree,
     * including "Add to Group…" and "Manage Groups…", which create nothing. `GroupedItemsListRenderer`
     * suppresses a separator at index 0 only when its caption is blank, so a captioned one does
     * render above the top item — the bare [ListSeparator] below the swatches still would not.
     */
    override fun getSeparatorAbove(value: Entry): ListSeparator? = when {
        value === values.firstOrNull() -> ListSeparator("New Group")
        value === firstBelowSwatches -> ListSeparator()
        // The one place the popup says which group the right-clicked file is already in. Without
        // it, "Remove from Group" is the only hint that the file is assigned at all, and it names
        // nothing — so the answer to "which group is this tab in?" was nowhere in the UI once the
        // tab's own tint stopped being enough to tell two similar colours apart.
        value == Entry.RemoveFromGroup ->
            service.groupFor(file.url)?.let { ListSeparator(currentGroupCaption(it.name)) }
        else -> null
    }

    override fun hasSubstep(value: Entry): Boolean =
        value == Entry.AddToGroup || value == Entry.ManageGroups

    override fun onChosen(selectedValue: Entry, finalChoice: Boolean): PopupStep<*>? =
        when (selectedValue) {
            is Entry.Preset -> {
                finalRunnable = Runnable {
                    promptForNameAndCreate(project, file, service, selectedValue.preset)
                }
                PopupStep.FINAL_CHOICE
            }
            // Applied straight from `onChosen`, not deferred to [getFinalRunnable] the way
            // [AddToGroupStep] is: this row opens no window of its own. The file it acts on is
            // unassigned by construction (see [buildEntries]), so no group can be left empty here
            // and there is no prompt to race the popup's close.
            is Entry.AddToActive -> {
                addToActiveGroup(project, listOf(file), selectedValue.group, service)
                PopupStep.FINAL_CHOICE
            }
            Entry.AddToGroup -> AddToGroupStep(project, file, service)
            Entry.ManageGroups -> ManageGroupsStep(project, service)
            Entry.RemoveFromGroup -> {
                finalRunnable = Runnable { removeFromGroup(project, service, file.url) }
                PopupStep.FINAL_CHOICE
            }
        }

    override fun getFinalRunnable(): Runnable? = finalRunnable

    private companion object {
        /**
         * [Entry.AddToActive] is offered only for a file that is in **no** group, which is exactly
         * the state auto-join would have claimed had the file not already been open — see
         * [addToActiveGroupLabel]. For a file that is already in one, the popup answers the same
         * need twice over a row below: its group is named in the "In group:" caption, and
         * "Add to Group…" moves it, active group marked "(active)" in that list.
         */
        fun buildEntries(service: TabGroupService, file: VirtualFile): List<Entry> {
            val assigned = service.groupFor(file.url) != null
            val entries = mutableListOf<Entry>()
            entries += effectivePresets().map { Entry.Preset(it) }
            if (!assigned) service.activeGroup()?.let { entries += Entry.AddToActive(it) }
            entries += Entry.AddToGroup
            if (service.groups().isNotEmpty()) entries += Entry.ManageGroups
            if (assigned) entries += Entry.RemoveFromGroup
            return entries
        }
    }
}

/** Nested step listing existing groups by name, each with its own colour dot. */
private class AddToGroupStep(
    private val project: Project,
    private val file: VirtualFile,
    private val service: TabGroupService,
) : BaseListPopupStep<TabGroup>("Add to Group", service.groups()) {

    private var finalRunnable: Runnable? = null

    override fun getTextFor(value: TabGroup): String = labelFor(value, service)

    override fun getIconFor(value: TabGroup): Icon = groupSwatch(value)

    /**
     * Deferred to [getFinalRunnable] rather than applied straight from `onChosen`, unlike the
     * one-line mutations elsewhere in this file: moving a file out of its previous group can empty
     * that group, and the prompt that follows is a modal — same reason [RootStep]'s preset click
     * defers.
     */
    override fun onChosen(selectedValue: TabGroup, finalChoice: Boolean): PopupStep<*>? {
        finalRunnable = Runnable {
            val emptied = service.assign(file.url, selectedValue)
            TabRepaint.request(project, file)
            TabGroupNotifier.notifyChanged(project)
            promptToDeleteEmptied(project, service, emptied)
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getFinalRunnable(): Runnable? = finalRunnable
}

/** "Manage Groups…"'s step: every existing group, each opening [GroupActionsStep]. */
private class ManageGroupsStep(
    private val project: Project,
    private val service: TabGroupService,
) : BaseListPopupStep<TabGroup>("Manage Groups", service.groups()) {

    override fun getTextFor(value: TabGroup): String =
        labelFor(value, service)

    override fun getIconFor(value: TabGroup): Icon = groupSwatch(value)

    override fun hasSubstep(value: TabGroup): Boolean = true

    override fun onChosen(selectedValue: TabGroup, finalChoice: Boolean): PopupStep<*>? =
        GroupActionsStep(project, selectedValue, service)
}

private sealed class GroupAction {
    data object Files : GroupAction()
    data object Rename : GroupAction()
    data object Recolor : GroupAction()
    data object ToggleActive : GroupAction()
    data object Delete : GroupAction()
}

/**
 * Per-group actions reached from [ManageGroupsStep]. "Files…", Rename and Delete all open a window
 * of their own — same [getFinalRunnable] deferral as [RootStep]'s preset click, for the same reason
 * (avoid racing the popup's close animation). Recolour instead opens [RecolorStep], a further list
 * step with no separate window involved, so it can apply and repaint straight from `onChosen`.
 *
 * "Files…" leads the list, with a bare separator under it: everything below that divider *changes*
 * the group, and reading what is in one should not sit among them.
 */
private class GroupActionsStep(
    private val project: Project,
    private val group: TabGroup,
    private val service: TabGroupService,
) : BaseListPopupStep<GroupAction>(group.name, buildActions(group, service)) {

    private var finalRunnable: Runnable? = null

    override fun getTextFor(value: GroupAction): String = when (value) {
        GroupAction.Files -> "Files…"
        GroupAction.Rename -> "Rename…"
        GroupAction.Recolor -> "Recolour…"
        GroupAction.ToggleActive ->
            if (service.activeGroupId() == group.id) "Clear Active Group" else "Mark as Active Group"
        GroupAction.Delete -> "Delete Group"
    }

    override fun getSeparatorAbove(value: GroupAction): ListSeparator? = when (value) {
        // Only when "Files…" is actually there to be separated from: an empty group has no file
        // list to offer, and a divider above the first row would be the renderer's job to suppress
        // rather than this step's to emit.
        GroupAction.Rename -> if (values.firstOrNull() == GroupAction.Files) ListSeparator() else null
        GroupAction.Delete -> ListSeparator()
        else -> null
    }

    override fun hasSubstep(value: GroupAction): Boolean = value == GroupAction.Recolor

    override fun onChosen(selectedValue: GroupAction, finalChoice: Boolean): PopupStep<*>? =
        when (selectedValue) {
            GroupAction.Files -> {
                finalRunnable = Runnable { showGroupFiles(project, group, service) }
                PopupStep.FINAL_CHOICE
            }
            GroupAction.Rename -> {
                finalRunnable = Runnable { promptForRenameAndApply(project, group, service) }
                PopupStep.FINAL_CHOICE
            }
            GroupAction.Recolor -> RecolorStep(project, group, service)
            GroupAction.ToggleActive -> {
                toggleActiveGroup(project, group, service)
                PopupStep.FINAL_CHOICE
            }
            GroupAction.Delete -> {
                finalRunnable = Runnable { confirmAndDelete(project, group, service) }
                PopupStep.FINAL_CHOICE
            }
        }

    override fun getFinalRunnable(): Runnable? = finalRunnable

    private companion object {
        /**
         * "Files…" is offered only when the group has members, the same way [RootStep] offers
         * "Manage Groups…" only once a group exists: a group can legitimately sit empty (removing
         * its last member offers to delete it, and the offer can be declined — see
         * [promptToDeleteEmptied]), and an item whose whole content is "nothing" is a dead end, not
         * an answer.
         */
        fun buildActions(group: TabGroup, service: TabGroupService): List<GroupAction> = buildList {
            if (service.membersOf(group.id).isNotEmpty()) add(GroupAction.Files)
            add(GroupAction.Rename)
            add(GroupAction.Recolor)
            add(GroupAction.ToggleActive)
            add(GroupAction.Delete)
        }
    }
}

/**
 * "Recolour…"'s step: applies immediately and repaints every member — no name prompt needed.
 * `internal`, not `private`: also opened directly (not just nested off [GroupActionsStep]) by the
 * PrismTabs tool window's own "Recolour…" toolbar action, rather than duplicating this.
 */
internal class RecolorStep(
    private val project: Project,
    private val group: TabGroup,
    private val service: TabGroupService,
) : BaseListPopupStep<PresetColor>("Recolour ${group.name}", effectivePresets()) {

    override fun getTextFor(value: PresetColor): String = value.label

    override fun getIconFor(value: PresetColor): Icon =
        ColorSwatchIcon(JBColor(value.light, value.dark))

    override fun onChosen(selectedValue: PresetColor, finalChoice: Boolean): PopupStep<*>? {
        service.recolor(group.id, selectedValue.light.rgb and 0xFFFFFF, selectedValue.dark.rgb and 0xFFFFFF)
        repaintFiles(project, service.membersOf(group.id))
        TabGroupNotifier.notifyChanged(project)
        return PopupStep.FINAL_CHOICE
    }
}

/**
 * "Files…"'s window: every file assigned to [group], by name, with the directory it sits in.
 *
 * A *fresh top-level* chooser rather than a further nested [PopupStep], which is the whole point of
 * this entry (see the plan's Phase 6 section and its rejected alternative). Nested, the answer to
 * "what is in this group?" would sit five cascading popup windows deep from the right-click, each
 * one the mouse has to stay inside; opened this way the visual depth stays at one window, and it
 * gets a title bar and speed search the way the platform's own file lists do. Exactly what the tool
 * window's "Recolour…" action already does with [RecolorStep], one step further: a chooser, not a
 * menu, because these rows are files rather than verbs.
 *
 * Membership is read once, here, and the resolved [VirtualFile]s with it — the renderer must not do
 * VFS lookups per paint, and a row's icon and its missing/present state have to agree.
 */
private fun showGroupFiles(project: Project, group: TabGroup, service: TabGroupService) {
    val vfm = VirtualFileManager.getInstance()
    val resolved = service.membersOf(group.id).associateWith { vfm.findFileByUrl(it) }
    val entries = memberFileRows(resolved.keys, project.basePath) { resolved[it] == null }
        .map { row -> MemberFile(row, resolved[row.url]?.fileType?.icon ?: AllIcons.FileTypes.Unknown) }

    JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
        .setTitle("Files in ${group.name}")
        .setRenderer(MemberFileRenderer())
        // Speed search over the whole rendered row, not just the name: the directory is on screen,
        // so typing part of it and having nothing narrow would read as the filter being broken.
        .setNamerForFiltering { it.row.path }
        .setItemChosenCallback { chooseMemberFile(project, group, service, it.row) }
        .setMovable(true)
        .setResizable(true)
        .createPopup()
        // Centred rather than positioned from the originating tab's `DataContext`: this runs from
        // `getFinalRunnable`, i.e. after the tab popup has closed, and a context captured before
        // that is no longer a trustworthy anchor. It is what the platform's own titled choosers do.
        .showCenteredInCurrentWindow(project)
}

/** One row of [showGroupFiles]' chooser: the pure [MemberFileRow] plus the icon it renders with. */
private class MemberFile(val row: MemberFileRow, val icon: Icon)

/**
 * A group member as the file list shows it: the file's own name, the project-relative directory it
 * sits in, and whether its URL still resolves to a file on disk.
 *
 * The name is the primary text and the directory is secondary, greyed — never the full path as the
 * primary text, which would make the popup as wide as the deepest package while burying the one
 * part the eye is scanning for. Every platform file list (Search Everywhere, Recent Files,
 * Navigate | File, Bookmarks) is shaped this way.
 *
 * [directory] is the *full* relative path, not the shortened one: [shortenedDirectory] trims it for
 * display, while [path] — which the missing-file prompt quotes — must stay complete enough to
 * identify the file being unassigned.
 */
internal data class MemberFileRow(
    val url: String,
    val name: String,
    val directory: String,
    val missing: Boolean,
) {
    /** Name and directory rejoined: what a message about this file quotes. */
    val path: String get() = if (directory.isEmpty()) name else "$directory/$name"
}

/**
 * [showGroupFiles]' rows, in display order, derived from the assignment URLs alone.
 *
 * Derived from the URL rather than from a resolved [VirtualFile] on purpose: assignments are keyed
 * by URL, and a member whose file is gone has no `VirtualFile` to ask — yet it is exactly the row
 * that has to be *shown*, not skipped. [missing] is injected so the whole shape stays testable
 * without a platform harness, the same trade [deleteConfirmationMessage] and [currentGroupCaption]
 * make.
 *
 * The sort is explicit because [TabGroupService.membersOf] returns map iteration order, which is
 * arbitrary after a `loadState`. Nothing depended on it before this list existed; a file list would
 * have been the first surface to expose it as jumbled. Directory first, then name — the order a
 * file tree would have shown them in — case-insensitively, with the URL as a final tiebreak so two
 * files differing only in case still order deterministically.
 */
internal fun memberFileRows(
    fileUrls: Collection<String>,
    projectBasePath: String?,
    missing: (String) -> Boolean,
): List<MemberFileRow> =
    fileUrls
        .map { url -> memberFileRow(url, projectBasePath, missing(url)) }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, MemberFileRow::directory)
                .thenBy(String.CASE_INSENSITIVE_ORDER, MemberFileRow::name)
                .thenBy(MemberFileRow::url),
        )

/**
 * Splits one assignment URL into the name and directory [MemberFileRow] renders.
 *
 * `VirtualFileManager.extractPath` rather than string surgery on the scheme: it is what turns
 * `file:///…` and `jar:///…!/…` alike into a plain path, and it is a static with no application
 * behind it, so this stays unit-testable. Both the path it yields and `Project.getBasePath` are in
 * the platform's system-independent form (forward slashes, drive letter and all), which is what
 * makes the prefix comparison below hold on Windows as well as here.
 *
 * A file outside the project keeps its absolute directory: shortening it against the user's home
 * would be a second, machine-dependent notion of "relative" for no gain, since such a member is
 * rare and its full location is the useful thing about it.
 */
private fun memberFileRow(fileUrl: String, projectBasePath: String?, missing: Boolean): MemberFileRow {
    val path = VirtualFileManager.extractPath(fileUrl).trimEnd('/')
    val parent = path.substringBeforeLast('/', "")
    val directory = when {
        projectBasePath == null -> parent
        parent == projectBasePath -> ""
        parent.startsWith("$projectBasePath/") -> parent.substring(projectBasePath.length + 1)
        else -> parent
    }
    return MemberFileRow(fileUrl, path.substringAfterLast('/'), directory, missing)
}

/**
 * A row's directory as it is painted: trimmed from the left once it is long enough to widen the
 * popup more than it informs.
 *
 * `shortenPathWithEllipsis` rather than a plain right-truncation, because the informative end of a
 * path is the *last* segment — the package the file actually lives in — and it is the platform's
 * own helper for exactly this, keeping the leading segment for orientation and eliding the middle.
 * The ellipsis symbol is asked for explicitly so it matches the single "…" this popup's own labels
 * use rather than three full stops, the same choice [currentGroupCaption] makes.
 */
internal fun shortenedDirectory(directory: String): String =
    StringUtil.shortenPathWithEllipsis(directory, MAX_DIRECTORY, true)

/** Longest directory [shortenedDirectory] renders in full, in characters. */
private const val MAX_DIRECTORY = 60

/**
 * A file row: type icon, name, greyed directory — and, for a member whose file is gone, the name in
 * the platform's error attributes *and* the word "missing".
 *
 * Both, not just the red: a selected row is repainted in the list's selection foreground, which
 * takes the red with it, and colour alone is not a reading anyone should have to rely on.
 */
private class MemberFileRenderer : ColoredListCellRenderer<MemberFile>() {

    override fun customizeCellRenderer(
        list: JList<out MemberFile>,
        value: MemberFile,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = value.icon
        appendMemberFile(value.row, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}

/**
 * Paints one member file row — name, greyed directory, and the "missing" suffix — onto whichever
 * [SimpleColoredComponent] is asking.
 *
 * Shared rather than written twice: the tool window's tree (Phase 6a) renders the same rows this
 * chooser does, and two copies of the row shape would drift the moment either surface changed. It
 * hangs off [SimpleColoredComponent] because that is the one base class [ColoredListCellRenderer]
 * and [com.intellij.ui.ColoredTreeCellRenderer] have in common.
 *
 * [nameAttributes] is the caller's choice for a file that is *present* — the tree emphasises the
 * members whose tab is currently open, this chooser does not — while a missing file overrides it
 * with the platform's error attributes either way.
 */
internal fun SimpleColoredComponent.appendMemberFile(
    row: MemberFileRow,
    nameAttributes: SimpleTextAttributes,
) {
    append(row.name, if (row.missing) SimpleTextAttributes.ERROR_ATTRIBUTES else nameAttributes)
    if (row.directory.isNotEmpty()) {
        append("  ${shortenedDirectory(row.directory)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
    if (row.missing) append("  ·  missing", SimpleTextAttributes.GRAYED_ATTRIBUTES)
}

/**
 * Activating a member row: open the file, or — when it is no longer there — offer to unassign it.
 *
 * The URL is re-resolved rather than trusting the row's own `missing` flag: both surfaces that call
 * this are live windows and a file can be deleted while one of them is showing. A row that was fine
 * and is now gone lands on the same prompt as one that was already missing when the list was built.
 *
 * `internal`, not `private`: the tool window's tree (Phase 6a) activates a file row through here
 * too, so double-clicking a member in the tree and choosing one in this popup's chooser behave
 * identically — including the missing-file prompt, which is the half that is easy to forget.
 */
internal fun chooseMemberFile(
    project: Project,
    group: TabGroup,
    service: TabGroupService,
    row: MemberFileRow,
) {
    val file = VirtualFileManager.getInstance().findFileByUrl(row.url)
    if (file == null) {
        promptToUnassignMissing(project, group, service, row)
        return
    }
    OpenFileDescriptor(project, file).navigate(true)
}

/**
 * The missing member's one affordance: say so, and offer to drop the assignment.
 *
 * This is real persisted state, not a display glitch — a file deleted while the IDE was closed
 * fires no VFS event, so [io.prismtabs.listener.TabGroupVfsListener] never gets to call
 * [TabGroupService.clearTree] and the assignment outlives the file, keeping its group non-empty.
 * The file list is the first surface anywhere that makes that visible, so it is also the first that
 * can offer to fix it. Unassigning can leave the group empty, which is [promptToDeleteEmptied]'s
 * business as everywhere else; no repaint is needed, since a file that does not exist has no tab.
 */
private fun promptToUnassignMissing(
    project: Project,
    group: TabGroup,
    service: TabGroupService,
    row: MemberFileRow,
) {
    val confirmed = MessageDialogBuilder.yesNo("Missing File", missingFileMessage(row.path, group.name))
        .yesText("Remove from Group")
        .noText("Keep")
        .icon(Messages.getWarningIcon())
        .ask(project)
    if (!confirmed) return
    val emptied = service.clear(row.url)
    TabGroupNotifier.notifyChanged(project)
    promptToDeleteEmptied(project, service, emptied)
}

/**
 * [promptToUnassignMissing]'s prompt. Split out as a pure function for the same reason
 * [deleteConfirmationMessage] is: the wording is then unit-testable without a platform harness.
 */
internal fun missingFileMessage(path: String, groupName: String): String =
    "\"$path\" is no longer on disk.\n\nRemove it from \"$groupName\"?"

/**
 * Marks [group] as the active ("sticky") group, or clears it if it already is — the single choke
 * point for Phase 3's inheritance toggle, mirroring `toggleCollapse` in
 * [io.prismtabs.ui.TabGroupsToolWindowFactory]'s file.
 *
 * *Marking* is the paid auto-join feature (see the README's free/paid split); *clearing* is not — a
 * lapsed subscriber must always be able to switch off a sticky group they set while subscribed,
 * even though [io.prismtabs.listener.TabGroupFileListener] already ignores it.
 *
 * `internal`, not `private`: the paywall uiTest drives this directly, for the same reason
 * [expandAllCollapsedGroups] is internal.
 *
 * [TabGroupNotifier.notifyChanged] because the tool window's row renderer marks the active group
 * and its list is long-lived: without this, marking a group active from a tab's popup would leave
 * an already-open tool window showing the previous group as active until something else changed.
 * ([ManageGroupsStep] needs no such refresh — it is rebuilt on every popup open.)
 */
internal fun toggleActiveGroup(project: Project, group: TabGroup, service: TabGroupService) {
    val clearing = service.activeGroupId() == group.id
    if (clearing || PrismTabsLicense.requirePaidFeature("Marking a tab group active")) {
        service.setActiveGroup(if (clearing) null else group.id)
        TabGroupNotifier.notifyChanged(project)
    }
}

/** `internal`, not `private`: reused directly by the tool window's own "Rename…" action. */
internal fun promptForRenameAndApply(project: Project, group: TabGroup, service: TabGroupService) {
    val dialog = RenameGroupDialog(project, group.name)
    if (!dialog.showAndGet()) return
    service.rename(group.id, dialog.newName())
    TabGroupNotifier.notifyChanged(project)
}

/**
 * `MessageDialogBuilder.yesNo` rather than `Messages.showYesNoDialog`: it lets the buttons read
 * "Delete" / "Cancel" instead of generic "Yes" / "No", matching the UI Guidelines' ellipsis-and-
 * naming rule ("actions should be as concrete as the platform's own equivalents" — see the
 * Project view's own file-delete confirmation) and its guidance that an irreversible default
 * action needs an unambiguous label, not a bare affirmative.
 *
 * The message itself is [deleteConfirmationMessage], split out so the hidden-group warning it
 * carries is unit-testable without a platform harness — the same trade
 * [io.prismtabs.license.PrismTabsLicense.decide] makes for the licence gate.
 *
 * `internal`, not `private`: reused directly by the tool window's own "Delete Group" action.
 */
internal fun confirmAndDelete(project: Project, group: TabGroup, service: TabGroupService) {
    val hidden = TabGroupVisibility.isHidden(project, service, group)
    val confirmed = MessageDialogBuilder.yesNo("Delete Tab Group", deleteConfirmationMessage(group, hidden))
        .yesText("Delete")
        .noText("Cancel")
        .icon(Messages.getWarningIcon())
        .asWarning()
        .ask(project)
    if (!confirmed) return
    val members = service.membersOf(group.id)
    service.delete(group.id)
    repaintFiles(project, members)
    TabGroupNotifier.notifyChanged(project)
}

/**
 * [confirmAndDelete]'s prompt. [hidden] is [TabGroupVisibility.isHidden] for the same group, passed
 * in rather than derived here so this stays a pure function of what it prints — the same trade
 * [currentGroupCaption] and [memberFileRows] make.
 *
 * A hidden group gets a second line, because deleting one destroys more than the first sentence
 * admits: [TabGroupService.delete] drops the whole group, and with it the
 * [TabGroup.rememberedTabUrls] record of what its tabs were — so nothing is left to reopen them
 * from. This path is reachable without a subscription ("Manage Groups…" is free, and
 * [ManageGroupsStep] lists hidden groups like any other), which is what earns the line its space: it
 * is the one way a lapsed user can lose the locked tool window's "reopen the tabs" escape hatch
 * ([showAllHiddenGroups]), and this dialog is the last point where they could still say no.
 *
 * Both conditions are checked, not [hidden] alone: a group hidden with nothing remembered — one
 * whose files have simply never been opened — has no tabs to lose, and a warning about zero tabs
 * would be noise. "Would reopen", not "closed", because the memory now outlives the act of hiding
 * and is just as likely to have been filled by closing those tabs one at a time.
 */
internal fun deleteConfirmationMessage(group: TabGroup, hidden: Boolean): String = buildString {
    append("Delete \"${group.name}\"? Its files will be unassigned.")
    val rememberedTabs = group.rememberedTabUrls.size
    if (hidden && rememberedTabs > 0) {
        append("\n\nThis group is hidden. The ")
        append(if (rememberedTabs == 1) "tab it would reopen" else "$rememberedTabs tabs it would reopen")
        append(" will be forgotten.")
    }
}

/**
 * The caption over "Remove from Group": which group the right-clicked file is currently in.
 *
 * A captioned [ListSeparator] rather than the step's own title, for exactly the reason [RootStep]
 * gives for "New Group" — the popup's title bar would label the whole tree, including the swatches
 * and "Add to Group…", which are about groups this file is *not* in.
 *
 * The name goes through [shortGroupName], which is where the length cap and its ellipsis are
 * explained. Split out as a pure function for the same reason [deleteConfirmationMessage] is: the
 * wording and the truncation are then unit-testable without a platform harness.
 */
internal fun currentGroupCaption(groupName: String): String = "In group: " + shortGroupName(groupName)

/**
 * The one-click route into the active group: "Add to Tab Group \"Auth\"".
 *
 * It exists because auto-join fires on `fileOpened`, and the platform does not open a file that is
 * already open — double-clicking such a file in the Project view only *selects* its tab, so the
 * file the user plainly asked for stays ungrouped while an identical gesture on a closed file joins
 * the active group. There is no event that separates "open this, it happens to be open already"
 * from an ordinary selection change (`FileEditorManagerEvent` carries no cause, and a Project view
 * double-click reaches `FileNavigator` without passing through the action system at all), so
 * auto-joining on selection would sweep in every tab the *platform* selects — the neighbour that
 * takes focus when a tab is closed, most of all. This row is the honest half of that: the same
 * assignment auto-join would have made, asked for explicitly. See README §"Joining the active
 * group by hand".
 *
 * "Tab Group", not the popup's shorter "Group", because [io.prismtabs.action.AddToActiveGroupAction]
 * renders the same label into the Project view's context menu — a generic menu shared with the rest
 * of the IDE, where "Add to Group" alone names nothing. One label rather than one per surface: the
 * two cannot then drift, and this is the same reason [labelFor]'s "(active)" suffix is shared.
 */
internal fun addToActiveGroupLabel(groupName: String): String =
    "Add to Tab Group \"${shortGroupName(groupName)}\""

/**
 * A group name cut down to what a popup row can carry, ellipsis and all.
 *
 * Group names are free text with only a non-blank check on them, and a popup sizes itself to its
 * widest row, so an unbounded name would stretch the swatch row and every other entry along with
 * it. [MAX_GROUP_NAME] is a plain cap rather than a measured one: unlike `CAPTION_BOTTOM_GAP` there
 * is no platform value to match here, only a width past which a row stops being scannable.
 *
 * The ellipsis is passed explicitly so it is the same "…" the popup's own labels use ("Add to
 * Group…", "Rename…") rather than the three full stops `StringUtil`'s shorter overload substitutes.
 */
private fun shortGroupName(groupName: String): String =
    StringUtil.shortenTextWithEllipsis(groupName, MAX_GROUP_NAME, 0, "…")

/** Longest group name [shortGroupName] leaves untouched, in characters. */
private const val MAX_GROUP_NAME = 40

/**
 * Puts [files] in [group] — what both surfaces of the active-group shortcut do, the popup's
 * [Entry.AddToActive] row and [io.prismtabs.action.AddToActiveGroupAction] alike.
 *
 * [filesToAdd] does the filtering here rather than at each call site, which is what makes this safe
 * to run without the empty-group prompt every other assignment path carries: a file that is already
 * in a group is never touched, so no group can lose its last member to this.
 */
internal fun addToActiveGroup(
    project: Project,
    files: Collection<VirtualFile>,
    group: TabGroup,
    service: TabGroupService,
) {
    val added = filesToAdd(files, service)
    if (added.isEmpty()) return
    added.forEach {
        service.assign(it.url, group)
        TabRepaint.request(project, it)
    }
    TabGroupNotifier.notifyChanged(project)
}

/**
 * The files [addToActiveGroup] would actually move — the shortcut's whole rule, in one place, so
 * that what an action greys itself out over and what it then does cannot disagree.
 *
 * Files already in a group are left where they are: the shortcut stands in for auto-join, which
 * likewise never moves an assigned file, and a Project view multi-selection that quietly emptied
 * two other groups would be exactly the silent reassignment this feature exists to avoid. A
 * directory is dropped because an assignment is keyed to a file URL and paints a tab — a directory
 * has neither, and would sit in the group as a member nothing can ever show.
 */
internal fun filesToAdd(files: Collection<VirtualFile>, service: TabGroupService): List<VirtualFile> =
    files.filter { !it.isDirectory && service.groupFor(it.url) == null }

/**
 * "Remove from Group"'s handler: unassign, repaint, and offer to delete a group left empty.
 *
 * Keyed by URL rather than by [VirtualFile], and `internal`, because the tool window's tree offers
 * the same item on a member row (Phase 6c) — and a tree row can name a file that is no longer on
 * disk, which has no `VirtualFile` to be reached through. Assignments are keyed by URL throughout
 * ([MemberFileRow] included), so the URL is the identity both surfaces already hold; the repaint is
 * simply skipped when nothing resolves, since a file that does not exist has no tab to repaint.
 */
internal fun removeFromGroup(project: Project, service: TabGroupService, fileUrl: String) {
    val emptied = service.clear(fileUrl)
    VirtualFileManager.getInstance().findFileByUrl(fileUrl)?.let { TabRepaint.request(project, it) }
    TabGroupNotifier.notifyChanged(project)
    promptToDeleteEmptied(project, service, emptied)
}

/**
 * Offers to delete groups that a just-completed removal left with no members — this plugin's
 * answer to Chrome dropping a tab group once its last tab is gone.
 *
 * Chrome deletes silently; this asks first, because a PrismTabs group is persisted data — a name,
 * a colour, a row in the tool window, all of it surviving restarts — rather than a strip
 * decoration, and the removal that emptied it may have been a misclick.
 *
 * Deliberately *not* driven by closing tabs: a group's members are files, not open editors, so a
 * group whose every tab is closed — which is to say every hidden group — is not empty. See
 * [io.prismtabs.service.AssignmentRemoval].
 *
 * One dialog for the whole batch, not one per group: deleting a directory can empty several groups
 * at once, and a queue of identical modals is the wrong way to ask. Membership is re-read here
 * rather than trusted from [emptied], since the VFS caller reaches this through an `invokeLater`
 * and a group can have been refilled, renamed or deleted outright in between.
 */
internal fun promptToDeleteEmptied(project: Project, service: TabGroupService, emptied: List<TabGroup>) {
    val stillEmpty = emptied.mapNotNull { service.group(it.id) }.filter { service.membersOf(it.id).isEmpty() }
    if (stillEmpty.isEmpty()) return

    val names = stillEmpty.joinToString(", ") { "\"${it.name}\"" }
    val single = stillEmpty.size == 1
    val confirmed = MessageDialogBuilder
        .yesNo(
            if (single) "Empty Tab Group" else "Empty Tab Groups",
            if (single) "$names has no tabs left. Delete the group?"
            else "These groups have no tabs left: $names. Delete them?",
        )
        .yesText(if (single) "Delete Group" else "Delete Groups")
        .noText("Keep")
        .icon(Messages.getQuestionIcon())
        .ask(project)
    if (!confirmed) return

    stillEmpty.forEach { service.delete(it.id) }
    TabGroupNotifier.notifyChanged(project)
}

/** Single-group overload: what [TabGroupService.clear] and [TabGroupService.assign] hand back. */
internal fun promptToDeleteEmptied(project: Project, service: TabGroupService, emptied: TabGroup?) =
    promptToDeleteEmptied(project, service, listOfNotNull(emptied))

/** Resolves each file URL back to a [VirtualFile] and requests a repaint. Callers capture the
 * URL list themselves (rather than re-reading [TabGroupService.membersOf]) when the mutation
 * that just happened — e.g. [TabGroupService.delete] — already removed those assignments.
 * `internal`, not `private`: also called by [io.prismtabs.listener.TabGroupVfsListener] after a
 * rename/move remap. */
internal fun repaintFiles(project: Project, fileUrls: Collection<String>) {
    val vfm = VirtualFileManager.getInstance()
    fileUrls.forEach { url -> vfm.findFileByUrl(url)?.let { TabRepaint.request(project, it) } }
}

/**
 * The popup's single group-creation path, reached by clicking a preset dot. The name field is
 * pre-filled with the colour's own label, so accepting the default is one extra keystroke rather
 * than a decision — the swatch row stays fast for people who only want a colour tag, while anyone
 * who wants "Auth" instead of "Blue" gets to say so before the group exists.
 *
 * Every call mints a fresh `UUID`, so two files given the same colour land in two *distinct*
 * groups. That is the intended reading of "New Group": putting a second file into an existing
 * group is what "Add to Group…" is for.
 *
 * Cancelling the prompt aborts the whole flow; no group is created and nothing is assigned.
 */
private fun promptForNameAndCreate(
    project: Project,
    file: VirtualFile,
    service: TabGroupService,
    preset: PresetColor,
) {
    val validator = object : InputValidator {
        override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
        override fun canClose(inputString: String?): Boolean = !inputString.isNullOrBlank()
    }
    val name = Messages.showInputDialog(
        project,
        "Group name:",
        "New Tab Group",
        Messages.getQuestionIcon(),
        preset.label,
        validator,
    ) ?: return

    val group = TabGroup(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        lightRgb = preset.light.rgb and 0xFFFFFF,
        darkRgb = preset.dark.rgb and 0xFFFFFF,
        order = service.groups().size,
    )
    val emptied = service.assign(file.url, group)
    TabRepaint.request(project, file)
    TabGroupNotifier.notifyChanged(project)
    promptToDeleteEmptied(project, service, emptied)
}
