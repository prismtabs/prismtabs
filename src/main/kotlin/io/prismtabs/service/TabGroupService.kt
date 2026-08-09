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

    fun assign(fileUrl: String, group: TabGroup) {
        groups = groups + (group.id to group)
        assignments = assignments + (fileUrl to group.id)
        recomputeCache()
    }

    fun clear(fileUrl: String) {
        assignments = assignments - fileUrl
        recomputeCache()
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
     * Phase 4a's "collapse = close-and-remember". [openFileUrls] is the ordered list of files that
     * were open, and belonged to this group, at the moment of collapse — gathered by the caller
     * (see [io.prismtabs.service.TabGroupCollapse]) since [TabGroupService] has no
     * `FileEditorManager` access. A no-op if the group doesn't exist.
     */
    fun collapse(groupId: String, openFileUrls: List<String>) {
        val group = groups[groupId] ?: return
        groups = groups + (groupId to group.copy(collapsed = true, collapsedFileUrls = openFileUrls))
        recomputeCache()
    }

    /** Clears [TabGroup.collapsed] and [TabGroup.collapsedFileUrls]. A no-op if the group doesn't exist. */
    fun expand(groupId: String) {
        val group = groups[groupId] ?: return
        groups = groups + (groupId to group.copy(collapsed = false, collapsedFileUrls = emptyList()))
        recomputeCache()
    }

    /**
     * Remaps every assignment, and every group's [TabGroup.collapsedFileUrls], whose URL is
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
            if (group.collapsedFileUrls.isEmpty()) group
            else group.copy(collapsedFileUrls = group.collapsedFileUrls.map { remap(it, oldUrl, newUrl) })
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
                collapsed = group.collapsed
                collapsedFileUrls = group.collapsedFileUrls.toMutableList()
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
                entry.collapsed,
                entry.collapsedFileUrls.toList(),
                entry.order,
            )
        }
        assignments = state.assignments.toMap()
        recomputeCache()
    }
}
