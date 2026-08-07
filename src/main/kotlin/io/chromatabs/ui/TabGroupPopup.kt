package io.chromatabs.ui

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.chromatabs.model.PresetColor
import io.chromatabs.model.TabGroup
import io.chromatabs.model.TabGroupPalette
import io.chromatabs.service.TabGroupService
import io.chromatabs.service.TabRepaint
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

private sealed class Entry {
    class Preset(val preset: PresetColor) : Entry()
    data object AddToGroup : Entry()
    data object NewGroup : Entry()
    data object RemoveFromGroup : Entry()
}

private fun textFor(entry: Entry): String = when (entry) {
    is Entry.Preset -> entry.preset.label
    Entry.AddToGroup -> "Add to Group…"
    Entry.NewGroup -> "New Group…"
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
        value == Entry.AddToGroup || value == Entry.NewGroup

    override fun onChosen(selectedValue: Entry, finalChoice: Boolean): PopupStep<*>? =
        when (selectedValue) {
            is Entry.Preset -> {
                quickAssign(project, file, service, selectedValue.preset)
                PopupStep.FINAL_CHOICE
            }
            Entry.AddToGroup -> AddToGroupStep(project, file, service)
            Entry.NewGroup -> NewGroupColorStep(project, file, service)
            Entry.RemoveFromGroup -> {
                service.clear(file.url)
                TabRepaint.request(project, file)
                PopupStep.FINAL_CHOICE
            }
        }

    private companion object {
        fun buildEntries(service: TabGroupService, file: VirtualFile): List<Entry> {
            val entries = mutableListOf<Entry>()
            entries += TabGroupPalette.PRESETS.map { Entry.Preset(it) }
            entries += Entry.AddToGroup
            entries += Entry.NewGroup
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
) : BaseListPopupStep<PresetColor>("New Group", TabGroupPalette.PRESETS) {

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
}
