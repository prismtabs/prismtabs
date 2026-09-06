package io.prismtabs.listener

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.prismtabs.license.PrismTabsLicense
import io.prismtabs.model.TabGroup
import io.prismtabs.service.TabGroupNotifier
import io.prismtabs.service.TabGroupService
import io.prismtabs.service.TabGroupVisibility
import io.prismtabs.service.TabRepaint

/**
 * Two jobs, both hanging off the same editor events.
 *
 * **Auto-join** is the plan's Phase 3 substitute for "a file opened from within a group's context
 * joins that group" — IntelliJ has no notion of editor context, so instead one group can be marked
 * *active* ([TabGroupService.setActiveGroup]) and every newly opened file not already assigned joins
 * it, until the group is deactivated or another group takes its place.
 *
 * **Remembering open tabs** keeps [TabGroup.rememberedTabUrls] current so that showing a hidden
 * group restores the arrangement it actually had. Recording only at the moment of hiding was the
 * earlier design and it left "Show Group" with nothing to reopen whenever the tabs had been closed
 * one at a time by hand — which, now that hidden is derived from the strip rather than stored (see
 * [TabGroupVisibility]), is a perfectly ordinary way for a group to end up hidden.
 *
 * That memory is written on editor **opens only** — this class deliberately has no `fileClosed`.
 * Narrowing it as tabs close was the design before, and it is what left "Show Group" reopening a
 * single file: closing a group's tabs one at a time rewrote the memory on every close, so by the
 * time the last one went the record had been whittled down to just that tab, and the empty-set skip
 * in [rememberOpenTabsOf] then preserved the whittled record rather than the arrangement the user
 * had. Recording on open alone holds the group's last full arrangement, and still cannot grow
 * without bound: the next open of any member re-records *the open set at that moment*, so a tab the
 * user closed and did not return to drops out the next time they open anything in the group.
 *
 * Registered via `<projectListeners>` in plugin.xml, not a manual `MessageBusConnection.subscribe`
 * in a startup activity — dynamic-plugin-safe, no `<component>` needed, and the platform handles
 * subscribing per-project for us.
 */
internal class TabGroupFileListener : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        val service = project.service<TabGroupService>()
        autoJoinActiveGroup(project, service, file)
        // After the auto-join, so a file that just joined the active group is counted as one of its
        // open tabs on this very event rather than only on the next one.
        rememberOpenTabsOf(project, service, service.groupFor(file.url)?.id)
    }

    private fun autoJoinActiveGroup(project: Project, service: TabGroupService, file: VirtualFile) {
        val active = service.activeGroup() ?: return
        if (service.groupFor(file.url) != null) return
        // Auto-join is paid (see the README's free/paid split). Checked here rather than only where
        // the group is marked active, so a subscription lapsing mid-session stops auto-joining
        // immediately instead of when the IDE next restarts — and checked last, after the two cheap
        // early returns, so the free tier never pays for stamp verification on an ordinary file open.
        if (!PrismTabsLicense.paidFeaturesEnabled()) return
        service.assign(file.url, active)
        TabRepaint.request(project, file)
        // Announce the membership change, exactly as every hand-driven assignment path announces
        // it (see TabGroupPopup.addToActiveGroup). The tool window also refreshes on `fileOpened`
        // of its own accord — but that is the *same* event this listener is handling, so whether
        // its tree sees the new member depends on which of the two subscribers the message bus
        // calls first, and that order is not ours to set: TabGroupFileListener is declared in
        // `projectListeners`, and the platform subscribes such listeners lazily, the first time the
        // topic's subscriber list is computed (CompositeMessageBus.subscribeLazyListeners). Called
        // in the other order the panel rebuilds the tree from the assignments as they stood a
        // moment before this auto-join, and the file only shows up on the next unrelated group
        // event — which is the defect this line closes. Publishing here puts a refresh after the
        // mutation whatever the subscriber order, and costs one extra rebuild of a few dozen rows
        // in the order that was already working.
        TabGroupNotifier.notifyChanged(project)
    }

    /**
     * Records what is open in [groupId] right now — unless that is nothing.
     *
     * The empty case is the one that has to be skipped rather than written: a group with no open
     * tabs is exactly a hidden group, and overwriting the memory there would erase the only record
     * of what to bring back. Now that only opens reach here the set is all but always non-empty —
     * the file whose opening triggered this is itself a member — so the guard is there for the
     * platform paths where `getOpenFiles()` does not yet list it, not as a routine branch.
     *
     * Deliberately **not** licence-gated, unlike auto-join: this is bookkeeping, and a lapsed
     * subscriber whose memory silently stopped updating would find the locked tool window's
     * "reopen the tabs" escape hatch restoring a stale set of files.
     */
    private fun rememberOpenTabsOf(project: Project, service: TabGroupService, groupId: String?) {
        if (groupId == null) return
        val openUrls = TabGroupVisibility.openMemberUrls(project, service, groupId)
        if (openUrls.isEmpty()) return
        service.rememberOpenTabs(groupId, openUrls)
    }
}
