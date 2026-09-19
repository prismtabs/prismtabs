package io.prismtabs.service

import com.intellij.openapi.components.service
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
 * Every call made *here* is declared on the public `FileEditorManager` interface itself
 * (`getOpenFiles()` — verified via `javap` on 2024.2), not an impl class, so no cast is needed.
 * Closing and reopening go through [TabStrip] rather than `closeFile`/`openFile` directly, for the
 * class-loader reason set out there; both directions were found leaking it from here.
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
     * Records what is open in [groupId] right now as its [TabGroup.rememberedTabUrls] — unless that
     * is nothing.
     *
     * Called whenever the group *gains* an open tab: a member's editor opening
     * ([io.prismtabs.listener.TabGroupFileListener]) or an already-open tab joining it (every
     * assignment path in [io.prismtabs.ui.TabGroupPopup]). The second matters as much as the first:
     * recorded on opens alone, a tab added to a group after its last open was missing from the
     * memory, and closing the group's tabs one at a time then showing it left that tab behind.
     *
     * Never on a close, which would whittle the memory down to the last survivor — see
     * [io.prismtabs.listener.TabGroupFileListener]. The empty case is skipped rather than written
     * for the same reason: a group with no open tabs is exactly a hidden group, and overwriting the
     * memory there would erase the only record of what to bring back.
     *
     * Deliberately **not** licence-gated: this is bookkeeping, and a lapsed subscriber whose memory
     * silently stopped updating would find the locked tool window's "reopen the tabs" escape hatch
     * restoring a stale set of files.
     */
    fun rememberOpenTabs(project: Project, service: TabGroupService, groupId: String) {
        val openUrls = openMemberUrls(project, service, groupId)
        if (openUrls.isEmpty()) return
        service.rememberOpenTabs(groupId, openUrls)
    }

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
     * The tabs close asynchronously, through [TabStrip], so the tool window is told once they have
     * gone rather than straight away — what it draws is read off the tab strip, as for [show].
     *
     * Recording the exact set being closed is what makes Hide followed by Show an identity even for
     * a group only half of whose files were open. Nothing in the close loop can undo it:
     * [io.prismtabs.listener.TabGroupFileListener] maintains [TabGroup.rememberedTabUrls] on editor
     * *opens* only, precisely so that closing tabs — here or one at a time by hand — never whittles
     * the memory down as it goes.
     */
    fun hide(project: Project, service: TabGroupService, group: TabGroup) {
        val openUrls = openMemberUrls(project, service, group.id)
        if (openUrls.isEmpty()) return
        service.rememberOpenTabs(group.id, openUrls)
        val virtualFileManager = VirtualFileManager.getInstance()
        val files = openUrls.mapNotNull { virtualFileManager.findFileByUrl(it) }
        if (files.isEmpty()) {
            TabGroupNotifier.notifyChanged(project)
            return
        }
        project.service<TabStrip>().close(files) { TabGroupNotifier.notifyChanged(project) }
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
        val virtualFileManager = VirtualFileManager.getInstance()
        val files = toOpen.mapNotNull { virtualFileManager.findFileByUrl(it) }
        if (files.isEmpty()) {
            // Every member has gone from disk since it was assigned. Nothing to reopen, but the
            // tool window still asked a question and deserves the answer it would have got before.
            TabGroupNotifier.notifyChanged(project)
            return
        }
        // Reopening is asynchronous — see [TabStrip]. The notification has to wait for it, because
        // what the tool window draws (`isHidden`) is read off the tab strip.
        project.service<TabStrip>().open(files, requestFocus = false) { TabGroupNotifier.notifyChanged(project) }
    }
}
