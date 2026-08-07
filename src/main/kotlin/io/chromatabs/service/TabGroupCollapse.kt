package io.chromatabs.service

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import io.chromatabs.model.TabGroup

/**
 * Phase 4a's "collapse = close-and-remember". IntelliJ has no group-chip to hide, so collapsing a
 * group means closing every currently open editor tab that belongs to it, after recording which
 * ones were open (in `FileEditorManager.getOpenFiles()` order) so [expand] can reopen exactly
 * those, in the same order. Lives outside [TabGroupService], not as methods on it, for the same
 * reason [TabRepaint] does: `FileEditorManager` only exists inside a live IDE, and the service
 * stays plain-JUnit testable.
 *
 * Unlike [TabRepaint], both calls here are declared on the public `FileEditorManager` interface
 * itself (`closeFile(VirtualFile)`, `openFile(VirtualFile, boolean)` — verified via `javap` on
 * 2024.2), not an impl class, so no cast is needed.
 */
internal object TabGroupCollapse {

    fun collapse(project: Project, service: TabGroupService, group: TabGroup) {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val memberUrls = fileEditorManager.openFiles
            .filter { service.groupFor(it.url)?.id == group.id }
            .map { it.url }
        service.collapse(group.id, memberUrls)
        val virtualFileManager = VirtualFileManager.getInstance()
        memberUrls.forEach { url -> virtualFileManager.findFileByUrl(url)?.let { fileEditorManager.closeFile(it) } }
        TabGroupNotifier.notifyChanged(project)
    }

    /** [group] must be the pre-expand [TabGroup] — its [TabGroup.collapsedFileUrls] is read before
     * [TabGroupService.expand] clears it. */
    fun expand(project: Project, service: TabGroupService, group: TabGroup) {
        service.expand(group.id)
        val fileEditorManager = FileEditorManager.getInstance(project)
        val virtualFileManager = VirtualFileManager.getInstance()
        group.collapsedFileUrls.forEach { url ->
            virtualFileManager.findFileByUrl(url)?.let { fileEditorManager.openFile(it, false) }
        }
        TabGroupNotifier.notifyChanged(project)
    }
}
