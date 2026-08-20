package io.prismtabs.listener

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupNotifier
import io.prismtabs.service.TabGroupService
import io.prismtabs.ui.promptToDeleteEmptied
import io.prismtabs.ui.repaintFiles

/**
 * The Phase 1 TODO: assignments are keyed by [com.intellij.openapi.vfs.VirtualFile.getUrl] (see
 * [TabGroupService]'s doc), which changes on rename or move. This listener catches both — a
 * rename surfaces as [VFilePropertyChangeEvent] with [VFilePropertyChangeEvent.isRename], a move
 * to a different directory as [VFileMoveEvent] — and hands the old/new URL pair to
 * [TabGroupService.remapUrl] instead of letting the assignment orphan silently.
 *
 * Old/new URLs are rebuilt with [VirtualFileManager.constructUrl] from each event's own
 * `oldPath`/`newPath`, the same construction `VirtualFile.getUrl()` itself uses — not read off
 * `VirtualFile.url` before/after, since [BulkFileListener.after] only sees the file in its
 * post-change state.
 *
 * Deletion is the third VFS event that touches an assignment, and is handled in [before] — see
 * that override's doc for why it cannot wait for [after] like the other two.
 *
 * Registered via `<projectListeners>` in plugin.xml against
 * [com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES]. That topic lives on the *application*
 * message bus, but its `Topic.BroadcastDirection.TO_DIRECT_CHILDREN` (confirmed in the platform
 * bytecode) means it still reaches a `<projectListeners>` subscriber — the same shape the
 * platform's own bundled plugins use for project-scoped VFS listeners (e.g. javaFX's
 * `FxmlPresenceListener(Project)`). The `Project` constructor parameter is how such a listener is
 * bound to its project: `BulkFileListener.after` itself carries no project reference.
 *
 * Deliberately implements plain [BulkFileListener] (topic `VFS_CHANGES`), not
 * `BulkFileListenerBackgroundable` (topic `VFS_CHANGES_BG`): the platform's own doc on
 * `BulkFileListener` guarantees `after` is "invoked inside write-action" — i.e. on the EDT for
 * every version in this plugin's supported range — which is what lets [TabGroupService.remapUrl]
 * mutate its plain, non-`@Volatile` fields the same way every other mutator on that service
 * already does, with no extra synchronisation. `VFS_CHANGES_BG` would run off-EDT instead.
 */
internal class TabGroupVfsListener(private val project: Project) : BulkFileListener {

    /**
     * Deletion, handled in [before] rather than [after] because that is the only side of the write
     * action where the file being deleted is still valid enough to take a URL from.
     *
     * A deleted file's assignment has to go: left behind, it is what makes a group read as "1 tab"
     * in the tool window while pointing at a file that no longer exists — and dropping the last one
     * leaves the group genuinely empty, which is what the prompt is for.
     *
     * That prompt is deferred with `invokeLater`: [before] runs inside the write action performing
     * the deletion, and a modal cannot be shown from there. `nonModal` so it waits for whatever
     * dialog is driving the deletion (Safe Delete, for one) to close instead of stacking on top of
     * it, and `project.disposed` so a project closed in the meantime drops it.
     */
    override fun before(events: List<VFileEvent>) {
        val service = project.service<TabGroupService>()
        val removedUrls = mutableListOf<String>()
        val emptiedGroups = mutableListOf<TabGroup>()

        for (event in events) {
            if (event !is VFileDeleteEvent) continue
            val removal = service.clearTree(event.file.url)
            removedUrls += removal.removedUrls
            emptiedGroups += removal.emptiedGroups
        }

        if (removedUrls.isEmpty()) return
        TabGroupNotifier.notifyChanged(project)
        if (emptiedGroups.isEmpty()) return
        ApplicationManager.getApplication().invokeLater(
            { promptToDeleteEmptied(project, service, emptiedGroups) },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    override fun after(events: List<VFileEvent>) {
        val service = project.service<TabGroupService>()
        val repaintUrls = mutableListOf<String>()

        for (event in events) {
            val (oldUrl, newUrl) = urlChange(event) ?: continue
            repaintUrls += service.remapUrl(oldUrl, newUrl)
        }

        if (repaintUrls.isEmpty()) return
        repaintFiles(project, repaintUrls)
        TabGroupNotifier.notifyChanged(project)
    }

    private fun urlChange(event: VFileEvent): Pair<String, String>? = when {
        event is VFilePropertyChangeEvent && event.isRename ->
            urlOf(event.fileSystem.protocol, event.oldPath) to urlOf(event.fileSystem.protocol, event.newPath)
        event is VFileMoveEvent ->
            urlOf(event.fileSystem.protocol, event.oldPath) to urlOf(event.fileSystem.protocol, event.newPath)
        else -> null
    }

    private fun urlOf(protocol: String, path: String): String = VirtualFileManager.constructUrl(protocol, path)
}
