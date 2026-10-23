package io.prismtabs.service;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Opens a tab with no frame of this plugin's on the stack beneath the platform's open — in Java,
 * for the same reason and by the same means as {@link PlatformTabClose}, which documents both in
 * full. The two are deliberately the same shape: one queue in {@link TabStrip} drains through one
 * of these per request, and the only difference between them is which platform method the bound
 * method reference names.
 *
 * <p><b>Why a frame matters.</b> Opening a file builds several toolbars that live as long as the
 * editor, and {@code ActionToolbarImpl} stores a {@code Throwable} creation trace that pins the
 * class of every frame on the stack for the toolbar's life; see {@link TabStrip}. The blocking
 * open is worse than most in this respect, because it does not merely call into the platform and
 * return: it ends in {@code FileEditorManagerImplKt.waitBlockingAndPumpEdt}, which <em>pumps the
 * event queue</em> until the editor is ready. The coroutine that builds the editor was already
 * dispatched onto the EDT and would otherwise run on a stack rooted at
 * {@code EventQueue.dispatchEvent}; pumping runs it nested inside this call instead, so whatever
 * is on the stack here ends up underneath it. Handing the open over as a bound method reference is
 * what makes that harmless — there is nothing of this plugin's on the stack to be recorded.
 *
 * <p><b>Why the public API and not the suspending one.</b> The obvious alternative is
 * {@code FileEditorManagerEx.openFile(file, FileEditorOpenOptions, Continuation)}, whose whole EDT
 * half is wrapped in {@code withContext(Dispatchers.EDT)} and so also leaves the caller's frames
 * behind. Both that overload and {@code FileEditorOpenOptions} itself are
 * {@code @ApiStatus.Internal}, which JetBrains Marketplace rejects — see engineering notes
 * §"Internal API: what the Marketplace asked for". {@code FileEditorManager.openFile(file,
 * focusEditor)} is the public, unannotated equivalent on every IDE in the {@code verifyPlugin}
 * matrix, and it is an equivalent in the literal sense: its bytecode builds
 * {@code FileEditorOpenOptions} with {@code requestFocus = focusEditor} and every other field
 * defaulted, which is byte for byte what this plugin used to build by hand (checked with
 * {@code javap} per CLAUDE.md on 2024.2 and on 263, the two ends of the matrix).
 *
 * <p>That swap is also what retires {@code PlatformOpenOptions}: with the options object gone
 * there is no Kotlin default-argument synthetic left to avoid on this path.
 *
 * @see PlatformTabClose
 */
final class PlatformTabOpen {

    private PlatformTabOpen() {
    }

    /**
     * Opens {@code file}, as {@link FileEditorManager#openFile(VirtualFile, boolean)} does, on
     * whatever thread {@code executor} runs its tasks on — which must be the EDT, with the
     * write-intent lock that call requires.
     *
     * <p>{@code thenCombineAsync} rather than {@code thenApplyAsync} because the platform's method
     * takes two arguments: the second future carries {@code requestFocus}, so the consumer can stay
     * a <em>bound method reference</em> ({@code BiFunction<VirtualFile, Boolean, FileEditor[]>},
     * with the unboxing done by the {@code LambdaMetafactory}-generated hidden class). A lambda
     * with a body closing over the flag would compile to a synthetic {@code lambda$} method in this
     * class, and that method's frame is precisely what must not be on the stack;
     * {@code PlatformStackHandOffTest} holds this class to having no synthetic methods for that
     * reason.
     */
    static CompletableFuture<FileEditor[]> openFile(
        FileEditorManager manager,
        VirtualFile file,
        boolean requestFocus,
        Executor executor
    ) {
        return CompletableFuture.completedFuture(file)
            .thenCombineAsync(CompletableFuture.completedFuture(requestFocus), manager::openFile, executor);
    }
}
