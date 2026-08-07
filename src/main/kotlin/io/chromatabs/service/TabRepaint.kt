package io.chromatabs.service

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Forces the editor tab strip to re-ask TabGroupColorProvider for this file's colour.
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
        (FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl)?.queueUpdateFile(file)
    }
}
