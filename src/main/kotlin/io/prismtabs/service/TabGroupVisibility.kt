package io.prismtabs.service

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import io.prismtabs.model.TabGroup

/**
 * What "hidden" means, and the two operations that move a group in and out of it.
 *
 * Chrome hides a group behind a collapsed chip; IntelliJ has no chip, so hiding is
 * *close-and-remember*: [hide] closes every open tab belonging to the group, and [show] reopens
 * what was last open in it. Presented to the user as Hide/Show throughout — "collapse/expand" would
 * collide with expanding a *tree row* in the tool window, which shows a group's files and touches
 * no tabs.
 *
 * The state itself is **derived, never stored** ([isHidden]): a group is hidden exactly when it has
 * members and none of them is open. That is deliberate, and it is the whole point of this object.
 * A stored flag says what the user last asked for; the tab strip says what is actually there, and
 * the two disagree the moment a member is reopened from the tool window's tree — which is the
 * ordinary way to use that tree. Deriving it means reopening one member un-hides the group,
 * closing the last tab by hand hides it, and "Show Group" is never a no-op that only clears a word.
 *
 * Lives outside [TabGroupService], not as methods on it, for the same reason [TabRepaint] does:
 * `FileEditorManager` only exists inside a live IDE, and the service stays plain-JUnit testable.
 * Every call used here is declared on the public `FileEditorManager` interface itself
 * (`getOpenFiles()`, `closeFile(VirtualFile)`, `openFile(VirtualFile, boolean)` — verified via
 * `javap` on 2024.2), not an impl class, so no cast is needed.
 */
internal object TabGroupVisibility {

    /**
     * The URLs of [groupId]'s members that are open right now, in `FileEditorManager`'s own tab
     * order — which is the order [show] must reopen them in for the strip to come back as it was.
     */
    fun openMemberUrls(project: Project, service: TabGroupService, groupId: String): List<String> =
        FileEditorManager.getInstance(project).openFiles
            .map { it.url }
            .filter { service.groupFor(it)?.id == groupId }

    /**
     * Whether [group] currently reads as hidden: it has members, and not one of them is open.
     *
     * An *empty* group is never hidden — it has nothing to show or hide, and calling it hidden would
     * put the word on every group the user has just emptied.
     */
    fun isHidden(project: Project, service: TabGroupService, group: TabGroup): Boolean =
        service.membersOf(group.id).isNotEmpty() && openMemberUrls(project, service, group.id).isEmpty()

    /**
     * Closes every open tab belonging to [group], after which [isHidden] reports it hidden.
     *
     * The recording happens **after** the closes, not before, and that order is load-bearing: each
     * `closeFile` notifies [io.prismtabs.listener.TabGroupFileListener], which keeps
     * [TabGroup.rememberedTabUrls] up to date as tabs come and go. Recording first would have that
     * upkeep overwrite this list with whatever was still open midway through the loop — hide two
     * tabs and only the second would ever come back. Recording last wins whether those
     * notifications arrive synchronously (they do) or not, because the upkeep never records an
     * empty list and every member is closed by the time it could run again.
     */
    fun hide(project: Project, service: TabGroupService, group: TabGroup) {
        val openUrls = openMemberUrls(project, service, group.id)
        if (openUrls.isEmpty()) return
        val fileEditorManager = FileEditorManager.getInstance(project)
        val virtualFileManager = VirtualFileManager.getInstance()
        openUrls.forEach { url -> virtualFileManager.findFileByUrl(url)?.let { fileEditorManager.closeFile(it) } }
        service.rememberOpenTabs(group.id, openUrls)
        TabGroupNotifier.notifyChanged(project)
    }

    /**
     * Reopens [group]'s tabs: the ones it remembers, or all of its files if it remembers none.
     *
     * The memory is re-read from [service] rather than taken off the passed [group], which may be a
     * snapshot from before some other change, and it is filtered against current membership — a
     * file that has since moved to another group must not be reopened as part of this one.
     *
     * The fallback matters for a group that has never had a tab open this session — a fresh
     * project, or one whose files were all closed before PrismTabs ever saw them. "Show Group" with
     * nothing remembered would otherwise be a dead gesture on exactly the row that most looks like
     * it should do something. Sorted by URL so the fallback is at least deterministic; the
     * remembered list needs no sorting, since it is already in tab order.
     */
    fun show(project: Project, service: TabGroupService, group: TabGroup) {
        val members = service.membersOf(group.id).toSet()
        val remembered = service.group(group.id)?.rememberedTabUrls.orEmpty().filter { it in members }
        val toOpen = remembered.ifEmpty { members.sorted() }
        if (toOpen.isEmpty()) return
        val fileEditorManager = FileEditorManager.getInstance(project)
        val virtualFileManager = VirtualFileManager.getInstance()
        toOpen.forEach { url -> virtualFileManager.findFileByUrl(url)?.let { fileEditorManager.openFile(it, false) } }
        TabGroupNotifier.notifyChanged(project)
    }
}
