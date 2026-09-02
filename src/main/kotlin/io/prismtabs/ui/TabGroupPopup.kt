package io.prismtabs.ui

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
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
 * Group" (only when the file is currently assigned).
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

private sealed class Entry {
    class Preset(val preset: PresetColor) : Entry()
    data object AddToGroup : Entry()
    data object ManageGroups : Entry()
    data object RemoveFromGroup : Entry()
}

private fun textFor(entry: Entry): String = when (entry) {
    is Entry.Preset -> entry.preset.label
    Entry.AddToGroup -> "Add to Group…"
    Entry.ManageGroups -> "Manage Groups…"
    Entry.RemoveFromGroup -> "Remove from Group"
}

private fun iconFor(entry: Entry): Icon? = when (entry) {
    is Entry.Preset -> ColorSwatchIcon(JBColor(entry.preset.light, entry.preset.dark))
    else -> null
}

private class RootStep(
    private val project: Project,
    private val file: VirtualFile,
    private val service: TabGroupService,
) : BaseListPopupStep<Entry>("", buildEntries(service, file)) {

    private var finalRunnable: Runnable? = null

    override fun getTextFor(value: Entry): String = textFor(value)

    override fun getIconFor(value: Entry): Icon? = iconFor(value)

    /**
     * The "New Group" caption over the swatch row is a captioned [ListSeparator] on the *first*
     * entry rather than the step's own title: the popup's title bar would label the whole tree,
     * including "Add to Group…" and "Manage Groups…", which create nothing. `GroupedItemsListRenderer`
     * suppresses a separator at index 0 only when its caption is blank, so a captioned one does
     * render above the top item — the bare [ListSeparator] before "Add to Group…" still would not.
     */
    override fun getSeparatorAbove(value: Entry): ListSeparator? = when {
        value === values.firstOrNull() -> ListSeparator("New Group")
        value == Entry.AddToGroup -> ListSeparator()
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
            Entry.AddToGroup -> AddToGroupStep(project, file, service)
            Entry.ManageGroups -> ManageGroupsStep(project, service)
            Entry.RemoveFromGroup -> {
                finalRunnable = Runnable { removeFromGroup(project, file, service) }
                PopupStep.FINAL_CHOICE
            }
        }

    override fun getFinalRunnable(): Runnable? = finalRunnable

    private companion object {
        fun buildEntries(service: TabGroupService, file: VirtualFile): List<Entry> {
            val entries = mutableListOf<Entry>()
            entries += effectivePresets().map { Entry.Preset(it) }
            entries += Entry.AddToGroup
            if (service.groups().isNotEmpty()) entries += Entry.ManageGroups
            if (service.groupFor(file.url) != null) entries += Entry.RemoveFromGroup
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

    override fun getIconFor(value: TabGroup): Icon =
        ColorSwatchIcon(JBColor(Color(value.lightRgb), Color(value.darkRgb)))

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

    override fun getIconFor(value: TabGroup): Icon =
        ColorSwatchIcon(JBColor(Color(value.lightRgb), Color(value.darkRgb)))

    override fun hasSubstep(value: TabGroup): Boolean = true

    override fun onChosen(selectedValue: TabGroup, finalChoice: Boolean): PopupStep<*>? =
        GroupActionsStep(project, selectedValue, service)
}

private sealed class GroupAction {
    data object Rename : GroupAction()
    data object Recolor : GroupAction()
    data object ToggleActive : GroupAction()
    data object Delete : GroupAction()
}

/**
 * Per-group actions reached from [ManageGroupsStep]. Rename and Delete both pop a modal — same
 * [getFinalRunnable] deferral as [RootStep]'s preset click, for the same reason (avoid racing the
 * popup's close animation). Recolour instead opens [RecolorStep], a further list step with no
 * modal involved, so it can apply and repaint straight from `onChosen`.
 */
private class GroupActionsStep(
    private val project: Project,
    private val group: TabGroup,
    private val service: TabGroupService,
) : BaseListPopupStep<GroupAction>(
    group.name,
    listOf(GroupAction.Rename, GroupAction.Recolor, GroupAction.ToggleActive, GroupAction.Delete),
) {

    private var finalRunnable: Runnable? = null

    override fun getTextFor(value: GroupAction): String = when (value) {
        GroupAction.Rename -> "Rename…"
        GroupAction.Recolor -> "Recolour…"
        GroupAction.ToggleActive ->
            if (service.activeGroupId() == group.id) "Clear Active Group" else "Mark as Active Group"
        GroupAction.Delete -> "Delete Group"
    }

    override fun getSeparatorAbove(value: GroupAction): ListSeparator? =
        if (value == GroupAction.Delete) ListSeparator() else null

    override fun hasSubstep(value: GroupAction): Boolean = value == GroupAction.Recolor

    override fun onChosen(selectedValue: GroupAction, finalChoice: Boolean): PopupStep<*>? =
        when (selectedValue) {
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
    val confirmed = MessageDialogBuilder.yesNo("Delete Tab Group", deleteConfirmationMessage(group))
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
 * [confirmAndDelete]'s prompt.
 *
 * A hidden group gets a second line, because deleting one destroys more than the first sentence
 * admits: [TabGroupService.delete] drops the whole group, and with it the
 * [TabGroup.collapsedFileUrls] record of what hiding closed — so those tabs are never reopened and
 * nothing is left to reopen them from. This path is reachable without a subscription ("Manage
 * Groups…" is free, and [ManageGroupsStep] lists hidden groups like any other), which is what earns
 * the line its space: it is the one way a lapsed user can lose the locked tool window's "reopen the
 * tabs" escape hatch ([expandAllCollapsedGroups]), and this dialog is the last point where they
 * could still say no.
 *
 * Both conditions are checked, not just [TabGroup.collapsed]: hiding a group none of whose files
 * were open records nothing, and a warning about zero tabs would be noise.
 */
internal fun deleteConfirmationMessage(group: TabGroup): String = buildString {
    append("Delete \"${group.name}\"? Its files will be unassigned.")
    val rememberedTabs = group.collapsedFileUrls.size
    if (group.collapsed && rememberedTabs > 0) {
        append("\n\nThis group is hidden. The ")
        append(if (rememberedTabs == 1) "tab it closed" else "$rememberedTabs tabs it closed")
        append(" will not be reopened.")
    }
}

/** "Remove from Group"'s handler: unassign, repaint, and offer to delete a group left empty. */
private fun removeFromGroup(project: Project, file: VirtualFile, service: TabGroupService) {
    val emptied = service.clear(file.url)
    TabRepaint.request(project, file)
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
 * group whose every tab is closed — which includes every collapsed group — is not empty. See
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
