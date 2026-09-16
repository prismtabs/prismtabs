package io.prismtabs.service

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Forces the editor tab strip to re-ask TabGroupColorProvider for a file's colour.
 *
 * No stable public API exists for this — see README §"repaint". `FileEditorManagerImpl
 * .queueUpdateFile` is the call verified in Phase 0 to actually move the tab *background*
 * (its sibling `updateFileColor` only touches foreground attributes). Both are declared on the
 * impl class, not the `FileEditorManagerEx` interface.
 *
 * Chosen approach: a compile-time-checked cast to the impl class, not reflection. Reflection
 * would hide a future removal of this method from the Plugin Verifier, which already checks the
 * full 2024.2-2026.2 matrix in `build.gradle.kts` on every build — a cast lets that machinery do
 * its job. The `as?` keeps this a no-op instead of a crash on any IDE where the cast fails
 * (headless test fixtures, or a future platform that slips past the verifier).
 */
internal object TabRepaint {

    fun request(project: Project, file: VirtualFile) {
        manager(project)?.queueUpdateFile(file)
    }

    /**
     * The same request for every tab currently on the strip — what a dynamic load or unload needs,
     * the one caller that cannot name its files: [io.prismtabs.listener.PrismTabsDynamicPluginListener]
     * is repainting *because* this plugin has just started, or is about to stop, answering for all
     * of them.
     *
     * `queueUpdateFile` is a queue, not a paint: it hands the file to `FileEditorManagerImpl`'s
     * own `MergingUpdateChannel` (confirmed in the platform bytecode) and the recolour happens a
     * beat later, on the platform's coroutine. That is what makes it safe to call from unload —
     * what is left on the queue is a [VirtualFile] and nothing of this plugin's, so the queue
     * cannot itself become the reference that keeps the class loader alive.
     */
    fun requestAll(project: Project) {
        val manager = manager(project) ?: return
        manager.openFiles.forEach(manager::queueUpdateFile)
    }

    private fun manager(project: Project): FileEditorManagerImpl? =
        FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl
}
