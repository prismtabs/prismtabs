package io.chromatabs.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.chromatabs.model.TabGroup
import io.chromatabs.model.TabGroupState
import java.awt.Color

/**
 * Owns tab-group assignments for a project: group id -> [TabGroup], file URL -> group id.
 *
 * Assignments are keyed by [com.intellij.openapi.vfs.VirtualFile.getUrl]. Renaming or moving a
 * file therefore silently drops its assignment — accepted for Phase 1, see README. Remapping on
 * rename via a VirtualFileListener is deferred to Phase 5.
 *
 * [colorFor] is read by TabGroupColorProvider on the EDT during tab painting, often. It must stay
 * a single map lookup: no locks, no I/O. [colorCache] is recomputed wholesale and swapped only
 * when an assignment actually changes.
 */
@Service(Service.Level.PROJECT)
@State(name = "TabGroupState", storages = [Storage("tabGroups.xml")])
class TabGroupService : PersistentStateComponent<TabGroupState> {

    private var groups: Map<String, TabGroup> = emptyMap()
    private var assignments: Map<String, String> = emptyMap()

    @Volatile
    private var colorCache: Map<String, Color> = emptyMap()

    fun colorFor(fileUrl: String): Color? = colorCache[fileUrl]

    fun assign(fileUrl: String, group: TabGroup) {
        groups = groups + (group.id to group)
        assignments = assignments + (fileUrl to group.id)
        recomputeCache()
    }

    fun clear(fileUrl: String) {
        assignments = assignments - fileUrl
        recomputeCache()
    }

    private fun recomputeCache() {
        colorCache = assignments.mapNotNull { (fileUrl, groupId) ->
            groups[groupId]?.let { group -> fileUrl to Color(group.colorRgb) }
        }.toMap()
    }

    override fun getState(): TabGroupState = TabGroupState().apply {
        groups = this@TabGroupService.groups.values.map { group ->
            TabGroupState.GroupEntry().apply {
                id = group.id
                name = group.name
                colorRgb = group.colorRgb
                collapsed = group.collapsed
                order = group.order
            }
        }.toMutableList()
        assignments = this@TabGroupService.assignments.toMutableMap()
    }

    override fun loadState(state: TabGroupState) {
        groups = state.groups.associate { entry ->
            entry.id to TabGroup(entry.id, entry.name, entry.colorRgb, entry.collapsed, entry.order)
        }
        assignments = state.assignments.toMap()
        recomputeCache()
    }
}
