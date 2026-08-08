package io.chromatabs.listener

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.chromatabs.service.TabGroupNotifier
import io.chromatabs.service.TabGroupService
import io.chromatabs.ui.repaintFiles

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
