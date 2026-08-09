package io.prismtabs.listener

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabRepaint

/**
 * The plan's Phase 3 substitute for "a file opened from within a group's context joins that
 * group" — IntelliJ has no notion of editor context, so instead one group can be marked *active*
 * ([TabGroupService.setActiveGroup]) and every newly opened file not already assigned joins it,
 * until the group is deactivated or another group takes its place.
 *
 * Registered via `<projectListeners>` in plugin.xml, not a manual `MessageBusConnection.subscribe`
 * in a startup activity — dynamic-plugin-safe, no `<component>` needed, and the platform handles
 * subscribing per-project for us.
 */
internal class TabGroupFileListener : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        val service = project.service<TabGroupService>()
        val active = service.activeGroup() ?: return
        if (service.groupFor(file.url) != null) return
        service.assign(file.url, active)
        TabRepaint.request(project, file)
    }
}
