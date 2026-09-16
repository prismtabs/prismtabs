package io.prismtabs.service;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Closes a tab with no frame of this plugin's on the stack beneath the platform's close — in Java,
 * because only a Java method reference can promise that.
 *
 * <p><b>Why a frame matters.</b> Closing the selected tab moves the selection, and from 2026.2 a
 * selection change can rebuild the tab strip's entry-point toolbar ({@code JBTabsImpl
 * .updateEntryPointToolbar}, which builds a fresh group, and so a fresh toolbar, whenever the tab
 * pane actions involved differ — a Markdown preview editor has some, a plain editor none). A
 * toolbar keeps its creation trace for life, and that trace pins the class of every frame on the
 * stack; see {@link TabStrip}. Unlike opening, the platform has no suspending close to hand the
 * work to, so the frame has to be kept off the stack another way.
 *
 * <p><b>How.</b> {@link CompletableFuture#thenAcceptAsync} hands a JDK task to {@code executor};
 * when that task runs, the stack is the executor's own, then {@code CompletableFuture}'s, then the
 * consumer, then the platform's close. The consumer is a bound method reference, which javac
 * compiles to an {@code invokedynamic} whose implementation <em>is</em>
 * {@code FileEditorManager.closeFile} — no synthetic {@code lambda$} method in this class — and
 * whose proxy is a hidden class, whose frames HotSpot leaves out of a {@code Throwable}'s backtrace.
 * So nothing of this plugin's is recorded. A Kotlin lambda, or a Java lambda with a body, would
 * each put a method of this plugin's own class on that stack instead; {@code
 * PlatformTabCloseTest} holds this class to having no synthetic methods for that reason.
 *
 * <p>Measured rather than assumed: a class loader whose only link to a retained trace was such a
 * method reference was collected on JDK 21 and on the IDE's JBR 25, and the same test with a lambda
 * in its place kept the loader alive. {@code PluginUnloadUiTest} covers the real thing.
 */
final class PlatformTabClose {

    private PlatformTabClose() {
    }

    /**
     * Closes {@code file} in every window it is open in, as {@link FileEditorManager#closeFile}
     * does, on whatever thread {@code executor} runs its tasks on — which must be the EDT, with
     * the write-intent lock that call requires.
     */
    static CompletableFuture<Void> closeFile(FileEditorManager manager, VirtualFile file, Executor executor) {
        return CompletableFuture.completedFuture(file).thenAcceptAsync(manager::closeFile, executor);
    }
}
