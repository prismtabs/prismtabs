package io.prismtabs.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Opens and closes editor tabs on the platform's behalf rather than on this plugin's stack — the one
 * thing that decides whether PrismTabs can be disabled or uninstalled without a restart.
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
 * **Closing does it too.** The 2026-09-16 snapshot held one toolbar, with a trace through
 * [TabGroupVisibility.hide] and `FileEditorManagerImpl.closeFile`: closing the selected tab moves
 * the selection, and from 2026.2 `JBTabsImpl.updateEntryPointToolbar` builds the tab strip a new
 * entry-point toolbar whenever the tab pane actions of the tabs involved differ — a Markdown
 * preview editor has some, a plain editor none. That toolbar lives until the selection next moves,
 * so a group hidden and left hidden kept the loader alive through the user's Disable. 2024.2 reuses
 * the toolbar and never showed it.
 *
 * **Why the blocking API cannot avoid it.** `FileEditorManager.openFile(file, focus)` does not
 * merely call into the platform and return: it ends in `FileEditorManagerImplKt
 * .waitBlockingAndPumpEdt`, which *pumps the event queue* until the editor is ready. The editor is
 * built by a coroutine that was already dispatched onto the EDT and would otherwise run on a stack
 * rooted at `EventQueue.dispatchEvent` with nothing of ours beneath it — but pumping dispatches it
 * nested inside our own call, so every frame of ours ends up underneath it after all. The stacks
 * in that snapshot show exactly this: the top forty frames are the platform's own coroutine
 * machinery, and PrismTabs appears only below `waitBlockingAndPumpEdt`. `closeFile` has no such
 * detour to blame; it simply runs on the caller's stack.
 *
 * Deferring with `invokeLater`, a queue, or a background thread does not help by itself: each
 * still runs *some* class of this plugin's — the runnable, the lambda — and that class is then the
 * one on the stack instead. Two things genuinely leave the stack behind, one per direction:
 *
 * - **Opening** calls the platform's *suspending* `openFile` from a background coroutine. That
 *   overload wraps its whole EDT half in `withContext(Dispatchers.EDT)` (confirmed in the platform
 *   bytecode), and a `withContext` from a background dispatcher genuinely dispatches: the frames
 *   below the suspension point are not rebuilt, so the editor, and every toolbar in it, is built on
 *   a clean EDT stack. The options are the platform's own defaults with `requestFocus` set, which
 *   is byte-for-byte what `FileEditorManager.openFile(file, focus)` builds (its bytecode passes
 *   argument 3 and defaults the other nine). They are built by [PlatformOpenOptions] rather than
 *   here, because constructing them *from Kotlin* is what broke this on every IDE from 2025.2 on —
 *   see that class.
 * - **Closing** has no suspending counterpart on any IDE from 2024.2 to 2026.2, so
 *   [PlatformTabClose] hands the platform's `closeFile` to the EDT as a Java method reference run by
 *   a JDK `CompletableFuture` task — the one shape whose frames are all the JDK's and the
 *   platform's. See that class.
 *
 * Opening and closing are otherwise unchanged; only the stack they happen on is.
 */
@Service(Service.Level.PROJECT)
internal class TabStrip(private val project: Project, private val scope: CoroutineScope) {

    private sealed class Request(val files: List<VirtualFile>, val onDone: () -> Unit)

    private class Open(files: List<VirtualFile>, val requestFocus: Boolean, onDone: () -> Unit) :
        Request(files, onDone)

    private class Close(files: List<VirtualFile>, onDone: () -> Unit) : Request(files, onDone)

    /**
     * One queue, drained by one coroutine, so that tabs open and close in the order they were asked
     * to.
     *
     * Ordering is not incidental here: "Show Group" hands over its files in the order the strip had
     * them, and the tool window's "reopen the tabs of every hidden group" escape hatch calls [open]
     * once per group. A `launch` per request would put that order at the mercy of which
     * `Dispatchers.Default` worker happened to pick each one up first, and the strip would come
     * back shuffled — a mutex alone does not fix that, because the race is over which coroutine
     * reaches the mutex, not what it does once it has it. A channel is ordered at `trySend`, which
     * happens on the caller's own thread, in the caller's own order. The same holds between the two
     * directions: a Show followed quickly by a Hide of the same group must close the tabs the Show
     * opened, not run first and leave them open.
     *
     * Unlimited, and sent to without suspending, because [open] and [close] are called from the EDT
     * and must not block there; the queue only ever holds a handful of entries.
     */
    private val requests = Channel<Request>(Channel.UNLIMITED)

    /**
     * Runs [PlatformTabClose]'s task on the EDT. This lambda itself only ever runs on the draining
     * coroutine's background thread, when the task is handed over, so it is never beneath a close.
     *
     * Non-modal, as the write-intent lock `closeFile` needs requires, and dropped if the project
     * closes first — in which case the close never completes, and nor does the wait on it, which
     * is then cancelled with [scope].
     */
    private val edt = Executor { task ->
        ApplicationManager.getApplication().invokeLater(task, ModalityState.nonModal(), project.disposed)
    }

    init {
        scope.launch {
            for (request in requests) {
                // Per request, and narrowly: a file that cannot be opened — gone from disk between
                // the caller resolving it and this running, or refused by every provider — must not
                // kill the queue and silently strand every later request.
                //
                // `Throwable`, not `Exception`, and that width is the whole point: a linkage error
                // against a platform member this plugin was compiled against is exactly the failure
                // that *does* arrive as an `Error`, and letting one past here does not fail one
                // request — it ends the coroutine draining this channel, so every open and close for
                // the rest of the session is stranded silently. That is what a `NoSuchMethodError`
                // from [PlatformOpenOptions]' constructor did in 1.4.14. Cancellation is rethrown
                // because it is not a failure to report: it is this scope being shut down with the
                // project, and swallowing it would leave the loop running past its own death.
                try {
                    val manager = FileEditorManagerEx.getInstanceEx(project)
                    when (request) {
                        is Open -> {
                            val options = PlatformOpenOptions.withRequestFocus(request.requestFocus)
                            request.files.forEach { manager.openFile(it, options) }
                        }
                        is Close -> request.files.forEach { PlatformTabClose.closeFile(manager, it, edt).await() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    thisLogger().warn("Could not ${if (request is Open) "open" else "close"} a tab group's files", e)
                }
                ApplicationManager.getApplication()
                    .invokeLater(Runnable(request.onDone), project.disposed)
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
        requests.trySend(Open(files, requestFocus, onOpened))
    }

    /**
     * Queues [files]' tabs to be closed, after which [onClosed] runs on the EDT — for the same
     * reason, and on the same terms, as [open]'s callback.
     */
    fun close(files: List<VirtualFile>, onClosed: () -> Unit = {}) {
        if (files.isEmpty()) return
        requests.trySend(Close(files, onClosed))
    }
}
