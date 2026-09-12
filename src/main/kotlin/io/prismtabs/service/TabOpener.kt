package io.prismtabs.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Opens editor tabs on the platform's behalf rather than on this plugin's stack — the one thing
 * that decides whether PrismTabs can be disabled or uninstalled without a restart.
 *
 * **Why this exists.** `ActionToolbarImpl`'s constructor stores `new Throwable("toolbar creation
 * trace")` in a private *final* `myCreationTrace` field (confirmed in the platform bytecode: one
 * `putfield`, never cleared). A `Throwable`'s `backtrace` holds the [Class] of every frame that
 * was on the stack, and a class reaches its loader — so every toolbar the platform builds while a
 * PrismTabs frame is on the stack pins this plugin's class loader for as long as that toolbar
 * lives. Opening a file builds several that live as long as the editor: the tab's own toolbar, the
 * tab strip's entry point, `EditorInspectionsActionToolbar`, and — for a Markdown or other
 * preview-capable file — `TextEditorWithPreview`'s and `LayoutActionsFloatingToolbar`'s.
 *
 * That is not hypothetical. It is what the 2026-09-12 "not unload-safe because class loader cannot
 * be unloaded" snapshot holds, read out of the `.hprof` itself: 12 live toolbars whose creation
 * traces name `ToggleVisibilityAction`/`TabGroupsToolWindowKt`/[TabGroupVisibility] (Show Group)
 * or `TabGroupsPanel`/`TabGroupPopupKt` (opening a member file from the tool window), plus 25
 * `CoroutineScopeKt$cancelOnDispose` disposables holding the same stacks.
 *
 * **Why the blocking API cannot avoid it.** `FileEditorManager.openFile(file, focus)` does not
 * merely call into the platform and return: it ends in `FileEditorManagerImplKt
 * .waitBlockingAndPumpEdt`, which *pumps the event queue* until the editor is ready. The editor is
 * built by a coroutine that was already dispatched onto the EDT and would otherwise run on a stack
 * rooted at `EventQueue.dispatchEvent` with nothing of ours beneath it — but pumping dispatches it
 * nested inside our own call, so every frame of ours ends up underneath it after all. The stacks
 * in that snapshot show exactly this: the top forty frames are the platform's own coroutine
 * machinery, and PrismTabs appears only below `waitBlockingAndPumpEdt`.
 *
 * Nothing a caller can do about that from the EDT helps. Deferring with `invokeLater`, a queue, or
 * a background thread all still run *some* class of this plugin's, and that class is then the one
 * on the stack instead. A coroutine is the one mechanism that genuinely leaves the stack behind:
 * when a suspending call dispatches, the frames below the suspension point are not rebuilt, so the
 * inner work runs with no caller of ours on the stack at all.
 *
 * **What this does, then.** It calls the platform's *suspending* `openFile` from a background
 * coroutine. That overload wraps its whole EDT half in `withContext(Dispatchers.EDT)` (confirmed
 * in the platform bytecode), and a `withContext` from a background dispatcher genuinely
 * dispatches — so the editor, and every toolbar in it, is built on a clean EDT stack.
 *
 * The options are the platform's own defaults with `requestFocus` set, which is byte-for-byte what
 * `FileEditorManager.openFile(file, focus)` builds (its bytecode passes argument 3 and defaults the
 * other nine). Opening is otherwise unchanged; only the stack it happens on is.
 */
@Service(Service.Level.PROJECT)
internal class TabOpener(private val project: Project, private val scope: CoroutineScope) {

    private class Request(
        val files: List<VirtualFile>,
        val requestFocus: Boolean,
        val onOpened: () -> Unit,
    )

    /**
     * One queue, drained by one coroutine, so that tabs land in the order they were asked for.
     *
     * Ordering is not incidental here: "Show Group" hands over its files in the order the strip had
     * them, and the tool window's "reopen the tabs of every hidden group" escape hatch calls [open]
     * once per group. A `launch` per request would put that order at the mercy of which
     * `Dispatchers.Default` worker happened to pick each one up first, and the strip would come
     * back shuffled — a mutex alone does not fix that, because the race is over which coroutine
     * reaches the mutex, not what it does once it has it. A channel is ordered at `trySend`, which
     * happens on the caller's own thread, in the caller's own order.
     *
     * Unlimited, and sent to without suspending, because [open] is called from the EDT and must not
     * block there; the queue only ever holds a handful of entries.
     */
    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in requests) {
                // Per request, and narrowly: a file that cannot be opened — gone from disk between
                // the caller resolving it and this running, or refused by every provider — must not
                // kill the queue and silently strand every later request.
                try {
                    val manager = FileEditorManagerEx.getInstanceEx(project)
                    val options = FileEditorOpenOptions().withRequestFocus(request.requestFocus)
                    request.files.forEach { manager.openFile(it, options) }
                } catch (e: Exception) {
                    thisLogger().warn("Could not open a tab group's files", e)
                }
                ApplicationManager.getApplication()
                    .invokeLater(Runnable(request.onOpened), project.disposed)
            }
        }
    }

    /**
     * Queues [files] to be opened, left to right, after which [onOpened] runs on the EDT.
     *
     * [onOpened] is where the caller's "and now tell the tool window" goes: it must run after the
     * tabs are actually on the strip, because what the tool window shows —
     * [TabGroupVisibility.isHidden] — is derived from them. It is dispatched through `invokeLater`
     * rather than a coroutine EDT dispatcher so that it arrives with the write-intent lock the
     * platform's own listeners expect, and it is dropped if the project closes first.
     */
    fun open(files: List<VirtualFile>, requestFocus: Boolean, onOpened: () -> Unit = {}) {
        if (files.isEmpty()) return
        requests.trySend(Request(files, requestFocus, onOpened))
    }
}
