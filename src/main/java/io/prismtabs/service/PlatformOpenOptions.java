package io.prismtabs.service;

import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions;

/**
 * Builds the platform's default {@link FileEditorOpenOptions} — in Java, because Kotlin cannot do
 * it in a way that survives an IDE upgrade.
 *
 * <p>{@code FileEditorOpenOptions} is a Kotlin class whose every constructor parameter has a
 * default value, and Kotlin compiles {@code FileEditorOpenOptions()} into a call to the
 * <em>synthetic defaults</em> constructor: every parameter, plus a bitmask and a
 * {@code DefaultConstructorMarker}. That constructor's descriptor changes whenever the platform
 * adds a field to the class, and it has three times since this plugin's 2024.2 compile baseline —
 * a {@code Function0<EditorComposite>} on 2025.2, a {@code boolean} on 2025.3, a
 * {@code FileEditorOpenOptionsHint} on 2026.1. Descriptors are resolved at link time, so the call
 * a 242 build emits cannot be found on any of them.
 *
 * <p>That is not hypothetical either: it is what 1.4.14 shipped, and it took Show Group with it.
 * The {@code NoSuchMethodError} is an {@link Error}, so it passed straight through
 * {@link TabStrip}'s per-request {@code catch} and killed the queue's coroutine outright — after
 * which no tab group could reopen its tabs, and no member file could be opened from the tool
 * window, for the rest of the session. Recorded on IU-262.10315.125 as
 * "Unhandled exception in [… CoroutineName(io.prismtabs.service.TabOpener) …]" — the class's name
 * before it closed tabs as well as opening them.
 *
 * <p>Java has no default arguments, so {@code new FileEditorOpenOptions()} here compiles to the
 * class's <strong>zero-argument</strong> constructor — which Kotlin generates precisely because
 * every parameter has one, and which is present unchanged on every IDE from 2024.2 to 2026.3
 * (checked with {@code javap} per CLAUDE.md, across all eight IDEs of the {@code verifyPlugin}
 * matrix). {@code withRequestFocus} is stable over the same range for the same reason: it declares
 * its one argument explicitly.
 *
 * <p>Reflection would sidestep the synthetic constructor too, and is the wrong trade: it hides the
 * call from the Plugin Verifier, which is the one check that catches a platform member going away
 * before a release does. Written this way, both references stay in the bytecode where
 * {@code verifyPlugin} tests them against every IDE in the matrix, and
 * {@code PlatformDefaultArgumentCallsTest} guards the Kotlin side from quietly reintroducing the synthetic.
 */
final class PlatformOpenOptions {

    private PlatformOpenOptions() {
    }

    /**
     * The platform's own defaults with {@code requestFocus} set — byte for byte what
     * {@code FileEditorManager.openFile(file, focus)} builds for itself.
     */
    static FileEditorOpenOptions withRequestFocus(boolean requestFocus) {
        return new FileEditorOpenOptions().withRequestFocus(requestFocus);
    }
}
