package io.prismtabs.service

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Forces the editor tab strip to re-ask [io.prismtabs.provider.TabGroupColorProvider] for a file's
 * colour — the live half of this plugin's whole feature, and the one thing `editorTabColorProvider`
 * itself gives no way to ask for directly.
 *
 * **The call.** `FileEditorManager.updateFilePresentation(VirtualFile)` is public, carries no
 * `@ApiStatus` annotation, and has been unchanged since at least 2024.2 (checked with `javap` per
 * CLAUDE.md on both ends of the matrix). Its `FileEditorManagerImpl` override is `updateFileName`'s
 * exact bytecode with one call added — `queueUpdateFile(file)` — so calling the public method does,
 * through the platform's own code, precisely what the internal one does, no-op included: both
 * silently do nothing for a file that is not open, which is correct here since a closed file has no
 * tab to repaint. The platform uses this call itself, e.g. from `DiffFileEditorBase` and
 * `com.intellij.vcs.log.impl.VcsLogEditorUtil` (confirmed present in the built distribution, not
 * merely declared).
 *
 * This was JetBrains Marketplace's own answer to the rejection this plugin had drafted
 * [IJPL-256289](https://youtrack.jetbrains.com/issue/IJPL-256289) over, and it is a better one than
 * that issue asks for: the recomputation is queued through `fileUpdateChannel` exactly as it always
 * was, so it inherits the platform's own thread handling (`readAction` off the EDT, apply back on
 * it) rather than needing this plugin to reimplement it. An earlier version of this class did
 * exactly that reimplementation, over `FileEditorManagerEx.windows` and `TabInfo` matched by
 * component identity — correct, but unnecessary once the one call above was found.
 */
internal object TabRepaint {

    fun request(project: Project, file: VirtualFile) {
        FileEditorManager.getInstance(project).updateFilePresentation(file)
    }

    /**
     * The same request for every open tab — what a dynamic load or unload needs, the one caller
     * that cannot name its files: [io.prismtabs.listener.PrismTabsDynamicPluginListener] is
     * repainting *because* this plugin has just started, or is about to stop, answering for all of
     * them.
     *
     * Synchronous, and safely so at unload: `updateFilePresentation` only queues the file onto the
     * platform's own `fileUpdateChannel` and returns, the same non-blocking hand-off
     * `queueUpdateFile` always was, so nothing of this plugin's is left on the queue — only a
     * [VirtualFile] — and the recolour itself happens a beat later, on the platform's coroutine.
     */
    fun requestAll(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.forEach(manager::updateFilePresentation)
    }
}
