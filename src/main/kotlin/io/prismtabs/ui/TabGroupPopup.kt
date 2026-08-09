package io.prismtabs.ui

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import io.prismtabs.model.PresetColor
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupNotifier
import io.prismtabs.service.TabGroupPaletteService
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabRepaint
import java.awt.Color
import java.util.UUID
import javax.swing.Icon

/**
 * The Chrome-style swatch popup: `AssignTabGroupAction`'s single entry point. Tree shape per the
 * plan's Phase 2 section — 8 preset dots, a separator, "Add to Group…" / "New Group…" (both
 * `hasSubstep`), and "Remove from Group" (only when the file is currently assigned).
 */
internal object TabGroupPopup {

    fun show(project: Project, file: VirtualFile, dataContext: DataContext) {
        val service = project.service<TabGroupService>()
        val step = RootStep(project, file, service)
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(dataContext)
    }
}

/**
 * The effective palette: [io.prismtabs.model.TabGroupPalette.PRESETS] with any customisation from
 * [TabGroupPaletteConfigurable] laid over it. `TabGroupPaletteService` is application-level, so this
 * is fetched ambiently rather than threaded through every step's constructor — same footing as
 * `VirtualFileManager.getInstance()` in [repaintFiles] below.
 */
private fun effectivePresets(): List<PresetColor> = service<TabGroupPaletteService>().presets()

private sealed class Entry {
    class Preset(val preset: PresetColor) : Entry()
    data object AddToGroup : Entry()
    data object NewGroup : Entry()
    data object ManageGroups : Entry()
    data object RemoveFromGroup : Entry()
}

private fun textFor(entry: Entry): String = when (entry) {
    is Entry.Preset -> entry.preset.label
    Entry.AddToGroup -> "Add to Group…"
    Entry.NewGroup -> "New Group…"
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

    override fun getTextFor(value: Entry): String = textFor(value)

    override fun getIconFor(value: Entry): Icon? = iconFor(value)

    override fun getSeparatorAbove(value: Entry): ListSeparator? =
        if (value == Entry.AddToGroup) ListSeparator() else null

    override fun hasSubstep(value: Entry): Boolean =
        value == Entry.AddToGroup || value == Entry.NewGroup || value == Entry.ManageGroups

    override fun onChosen(selectedValue: Entry, finalChoice: Boolean): PopupStep<*>? =
        when (selectedValue) {
            is Entry.Preset -> {
                quickAssign(project, file, service, selectedValue.preset)
                PopupStep.FINAL_CHOICE
            }
            Entry.AddToGroup -> AddToGroupStep(project, file, service)
            Entry.NewGroup -> NewGroupColorStep(project, file, service)
            Entry.ManageGroups -> ManageGroupsStep(project, service)
            Entry.RemoveFromGroup -> {
                service.clear(file.url)
                TabRepaint.request(project, file)
                TabGroupNotifier.notifyChanged(project)
                PopupStep.FINAL_CHOICE
            }
        }

    private companion object {
        fun buildEntries(service: TabGroupService, file: VirtualFile): List<Entry> {
            val entries = mutableListOf<Entry>()
            entries += effectivePresets().map { Entry.Preset(it) }
            entries += Entry.AddToGroup
            entries += Entry.NewGroup
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

    override fun getTextFor(value: TabGroup): String = value.name

    override fun getIconFor(value: TabGroup): Icon =
        ColorSwatchIcon(JBColor(Color(value.lightRgb), Color(value.darkRgb)))

    override fun onChosen(selectedValue: TabGroup, finalChoice: Boolean): PopupStep<*>? {
        service.assign(file.url, selectedValue)
        TabRepaint.request(project, file)
        TabGroupNotifier.notifyChanged(project)
        return PopupStep.FINAL_CHOICE
    }
}

/**
 * "New Group…"'s first step: pick a colour. The name prompt runs from [getFinalRunnable] rather
 * than inline in [onChosen] — deferring the modal `Messages.showInputDialog` until after this
 * popup has actually closed avoids a dialog racing the popup's own close animation.
 */
private class NewGroupColorStep(
    private val project: Project,
    private val file: VirtualFile,
    private val service: TabGroupService,
) : BaseListPopupStep<PresetColor>("New Group", effectivePresets()) {

    private var finalRunnable: Runnable? = null

    override fun getTextFor(value: PresetColor): String = value.label

    override fun getIconFor(value: PresetColor): Icon =
        ColorSwatchIcon(JBColor(value.light, value.dark))

    override fun onChosen(selectedValue: PresetColor, finalChoice: Boolean): PopupStep<*>? {
        finalRunnable = Runnable { promptForNameAndCreate(project, file, service, selectedValue) }
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
        if (value.id == service.activeGroupId()) "${value.name} (active)" else value.name

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
 * [getFinalRunnable] deferral as [NewGroupColorStep], for the same reason (avoid racing the
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
                service.setActiveGroup(if (service.activeGroupId() == group.id) null else group.id)
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
 * Tab Groups tool window's own "Recolour…" toolbar action, rather than duplicating this.
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
 * `internal`, not `private`: reused directly by the tool window's own "Delete Group" action.
 */
internal fun confirmAndDelete(project: Project, group: TabGroup, service: TabGroupService) {
    val confirmed = MessageDialogBuilder.yesNo("Delete Tab Group", "Delete \"${group.name}\"? Its files will be unassigned.")
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

/** Resolves each file URL back to a [VirtualFile] and requests a repaint. Callers capture the
 * URL list themselves (rather than re-reading [TabGroupService.membersOf]) when the mutation
 * that just happened — e.g. [TabGroupService.delete] — already removed those assignments.
 * `internal`, not `private`: also called by [io.prismtabs.listener.TabGroupVfsListener] after a
 * rename/move remap. */
internal fun repaintFiles(project: Project, fileUrls: Collection<String>) {
    val vfm = VirtualFileManager.getInstance()
    fileUrls.forEach { url -> vfm.findFileByUrl(url)?.let { TabRepaint.request(project, it) } }
}

private fun quickAssign(project: Project, file: VirtualFile, service: TabGroupService, preset: PresetColor) {
    val id = "preset-${preset.id}"
    val group = service.groups().find { it.id == id } ?: TabGroup(
        id = id,
        name = preset.label,
        lightRgb = preset.light.rgb and 0xFFFFFF,
        darkRgb = preset.dark.rgb and 0xFFFFFF,
        order = service.groups().size,
    )
    service.assign(file.url, group)
    TabRepaint.request(project, file)
    TabGroupNotifier.notifyChanged(project)
}

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
    service.assign(file.url, group)
    TabRepaint.request(project, file)
    TabGroupNotifier.notifyChanged(project)
}
