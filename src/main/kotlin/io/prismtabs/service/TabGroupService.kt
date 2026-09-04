package io.prismtabs.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.prismtabs.model.TabGroup
import io.prismtabs.model.TabGroupState

/**
 * Owns tab-group assignments for a project: group id -> [TabGroup], file URL -> group id.
 *
 * Assignments are keyed by [com.intellij.openapi.vfs.VirtualFile.getUrl], which changes on rename
 * or move. [io.prismtabs.listener.TabGroupVfsListener] catches both and calls [remapUrl] so the
 * assignment follows the file instead of being silently dropped — see [remapUrl]'s doc.
 *
 * [groupFor] is read by TabGroupColorProvider on the EDT during tab painting, often. It must stay
 * a single map lookup: no locks, no I/O. [colorCache] is recomputed wholesale and swapped only
 * when an assignment actually changes. It deliberately caches the [TabGroup] itself, not a
 * resolved `Color` — resolving `lightRgb`/`darkRgb` into a theme-aware colour is the read
 * boundary's job (see [TabGroup]'s doc), not this service's.
 *
 * A group is *empty* when it has no assigned files — which has nothing to do with how many of its
 * files are open. Closing a tab never touches an assignment, and
 * [io.prismtabs.service.TabGroupVisibility.hide] deliberately closes every one of a group's tabs
 * while keeping its members. The three removals that can genuinely empty a group all report it, so
 * the UI can offer to delete it: [clear], [assign] (moving the last member elsewhere) and
 * [clearTree] (the file being deleted on disk). See [AssignmentRemoval].
 *
 * [activeGroupId] is Phase 3's "sticky group": [io.prismtabs.listener.TabGroupFileListener] reads
 * it to decide which group a newly opened, unassigned file should join. It is deliberately **not**
 * part of [TabGroupState] and does not survive an IDE restart — unlike an assignment, "sticky" is a
 * session-scoped recording toggle, not data, and silently resuming it after a restart the user has
 * forgotten about would auto-colour files they never asked to group. Read on the EDT alongside
 * [groupFor], so it gets the same `@Volatile` treatment.
 */
@Service(Service.Level.PROJECT)
@State(name = "TabGroupState", storages = [Storage("tabGroups.xml")])
class TabGroupService : PersistentStateComponent<TabGroupState> {

    private var groups: Map<String, TabGroup> = emptyMap()
    private var assignments: Map<String, String> = emptyMap()

    @Volatile
    private var colorCache: Map<String, TabGroup> = emptyMap()

    @Volatile
    private var activeGroupId: String? = null

    fun groupFor(fileUrl: String): TabGroup? = colorCache[fileUrl]

    fun groups(): List<TabGroup> = groups.values.sortedBy { it.order }

    fun membersOf(groupId: String): List<String> = assignments.filterValues { it == groupId }.keys.toList()

    fun group(groupId: String): TabGroup? = groups[groupId]

    /**
     * Assigns [fileUrl] to [group], creating or updating the group itself in the same step.
     *
     * Returns the group [fileUrl] just *left*, if that move emptied it — moving a group's last
     * member into another group is one of the three ways a group ends up with nothing in it. Null
     * when the file was unassigned, when it stayed in the same group, and when the group it left
     * still has other members.
     */
    fun assign(fileUrl: String, group: TabGroup): TabGroup? {
        val previousId = assignments[fileUrl]
        groups = groups + (group.id to group)
        assignments = assignments + (fileUrl to group.id)
        recomputeCache()
        if (previousId == null || previousId == group.id) return null
        return groups[previousId]?.takeIf { membersOf(it.id).isEmpty() }
    }

    /** Unassigns [fileUrl]. Returns the group it left, if that removal emptied it. */
    fun clear(fileUrl: String): TabGroup? =
        removeAssignments { it == fileUrl }.emptiedGroups.firstOrNull()

    /**
     * Unassigns every URL in [fileUrls] as one removal — the tool window's multi-select
     * "Remove from Group".
     *
     * Not a loop over [clear], which would recompute the colour cache once per file and report a
     * group emptied by the *last* of its members while saying nothing about the others removed in
     * the same gesture. One pass answers with the whole batch's [AssignmentRemoval.emptiedGroups],
     * each group named once, which is what a single empty-group prompt needs.
     */
    fun clearAll(fileUrls: Collection<String>): AssignmentRemoval {
        val urls = fileUrls.toSet()
        return removeAssignments { it in urls }
    }

    /**
     * Unassigns [rootUrl] and every assignment nested under it as a directory — the deletion
     * counterpart to [remapUrl], prefix-matched for the same reason: deleting a directory fires a
     * single VFS event for the directory itself, not one per descendant.
     */
    fun clearTree(rootUrl: String): AssignmentRemoval =
        removeAssignments { it == rootUrl || it.startsWith("$rootUrl/") }

    /**
     * Drops every assignment whose URL [matches], and those same URLs from any group's
     * [TabGroup.rememberedTabUrls] — a file that no longer exists must not be reopened by
     * [io.prismtabs.service.TabGroupVisibility.show].
     *
     * The early return keeps the overwhelmingly common case free of allocation: this runs once per
     * deleted file, and almost none of them are assigned to anything.
     */
    private fun removeAssignments(matches: (String) -> Boolean): AssignmentRemoval {
        val removedUrls = assignments.keys.filter(matches)
        if (removedUrls.isEmpty() && groups.values.none { it.rememberedTabUrls.any(matches) }) {
            return AssignmentRemoval(emptyList(), emptyList())
        }
        val affectedIds = removedUrls.mapNotNull { assignments[it] }.toSet()
        assignments = assignments.filterKeys { !matches(it) }
        groups = groups.mapValues { (_, group) ->
            if (group.rememberedTabUrls.none(matches)) group
            else group.copy(rememberedTabUrls = group.rememberedTabUrls.filterNot(matches))
        }
        recomputeCache()
        val emptied = affectedIds.filter { membersOf(it).isEmpty() }.mapNotNull { groups[it] }
        return AssignmentRemoval(removedUrls, emptied)
    }

    fun rename(groupId: String, name: String) {
        val group = groups[groupId] ?: return
        groups = groups + (groupId to group.copy(name = name))
        recomputeCache()
    }

    fun recolor(groupId: String, lightRgb: Int, darkRgb: Int) {
        val group = groups[groupId] ?: return
        groups = groups + (groupId to group.copy(lightRgb = lightRgb, darkRgb = darkRgb))
        recomputeCache()
    }

    fun delete(groupId: String) {
        groups = groups - groupId
        assignments = assignments.filterValues { it != groupId }
        if (activeGroupId == groupId) activeGroupId = null
        recomputeCache()
    }

    /**
     * Records [openFileUrls] as [TabGroup.rememberedTabUrls] — the ordered files that were open, and
     * belonged to this group, as of now. Gathered by the caller
     * ([io.prismtabs.service.TabGroupVisibility.openMemberUrls]) since [TabGroupService] has no
     * `FileEditorManager` access. A no-op if the group doesn't exist.
     *
     * Callers only ever record a **non-empty** list: an empty one would mean "this group has no tabs
     * to restore", which is precisely the state the memory exists to survive. Nothing here enforces
     * that — the rule belongs with the callers that know why they are recording — but see
     * [io.prismtabs.listener.TabGroupFileListener] and [io.prismtabs.service.TabGroupVisibility.hide]
     * for both of them.
     *
     * The equality guard keeps an unchanged recording off the persisted state entirely: this runs on
     * every editor open and close, and most of them re-record exactly what is already there.
     */
    fun rememberOpenTabs(groupId: String, openFileUrls: List<String>) {
        val group = groups[groupId] ?: return
        if (group.rememberedTabUrls == openFileUrls) return
        groups = groups + (groupId to group.copy(rememberedTabUrls = openFileUrls))
        recomputeCache()
    }

    /**
     * Remaps every assignment, and every group's [TabGroup.rememberedTabUrls], whose URL is
     * [oldUrl] or lives under it as a directory, to [newUrl]. Called by
     * [io.prismtabs.listener.TabGroupVfsListener] after a rename or move.
     *
     * The prefix match (`"$oldUrl/"`) matters as much as the exact match: a directory rename fires
     * a single VFS event for the directory itself, not one per descendant, so every assignment
     * nested under it has to be recognised as changed even though only its parent was reported.
     *
     * Returns the new URLs of every remapped assignment, so the caller — which owns
     * `FileEditorManager`/`VirtualFile` lookups, unlike this platform-free service — knows which
     * currently-open tabs need a repaint.
     */
    fun remapUrl(oldUrl: String, newUrl: String): List<String> {
        if (oldUrl == newUrl) return emptyList()

        val remappedUrls = mutableListOf<String>()
        assignments = assignments.entries.associate { (url, groupId) ->
            val mapped = remap(url, oldUrl, newUrl)
            if (mapped != url) remappedUrls += mapped
            mapped to groupId
        }
        groups = groups.mapValues { (_, group) ->
            if (group.rememberedTabUrls.isEmpty()) group
            else group.copy(rememberedTabUrls = group.rememberedTabUrls.map { remap(it, oldUrl, newUrl) })
        }
        if (remappedUrls.isNotEmpty()) recomputeCache()
        return remappedUrls
    }

    private fun remap(url: String, oldUrl: String, newUrl: String): String = when {
        url == oldUrl -> newUrl
        url.startsWith("$oldUrl/") -> newUrl + url.substring(oldUrl.length)
        else -> url
    }

    fun activeGroupId(): String? = activeGroupId

    fun activeGroup(): TabGroup? = activeGroupId?.let { groups[it] }

    fun setActiveGroup(groupId: String?) {
        activeGroupId = groupId
    }

    private fun recomputeCache() {
        colorCache = assignments.mapNotNull { (fileUrl, groupId) ->
            groups[groupId]?.let { group -> fileUrl to group }
        }.toMap()
    }

    override fun getState(): TabGroupState = TabGroupState().apply {
        groups = this@TabGroupService.groups.values.map { group ->
            TabGroupState.GroupEntry().apply {
                id = group.id
                name = group.name
                lightRgb = group.lightRgb
                darkRgb = group.darkRgb
                rememberedTabUrls = group.rememberedTabUrls.toMutableList()
                order = group.order
            }
        }.toMutableList()
        assignments = this@TabGroupService.assignments.toMutableMap()
    }

    override fun loadState(state: TabGroupState) {
        groups = state.groups.associate { entry ->
            entry.id to TabGroup(
                entry.id,
                entry.name,
                entry.lightRgb,
                entry.darkRgb,
                entry.rememberedTabUrls.toList(),
                entry.order,
            )
        }
        assignments = state.assignments.toMap()
        recomputeCache()
    }
}

/**
 * What removing assignments actually did.
 *
 * [removedUrls] is the file URLs that were assigned and no longer are — empty when the removal
 * matched nothing, which is the caller's cue that there is nothing to repaint or announce.
 * [emptiedGroups] is those of the groups they belonged to that are now left with no members at
 * all: the cue to offer deleting them (see `io.prismtabs.ui.promptToDeleteEmptied`).
 */
data class AssignmentRemoval(val removedUrls: List<String>, val emptiedGroups: List<TabGroup>)
