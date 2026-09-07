package io.prismtabs.listener

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.IconLoader
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Lets this plugin actually unload. Without it, uninstalling (or updating, or disabling) PrismTabs
 * leaves the plugin's class loader alive, which the platform reports as "not unload-safe because
 * class loader cannot be unloaded", dumps a ~400 MB heap snapshot for, and recovers from only by
 * asking the user to restart the IDE.
 *
 * Nothing this plugin itself holds is at fault in either case below. Both are *application-global
 * singletons owned by the platform or the JDK* that keep a strong reference to an object whose
 * class came from this plugin's loader — and neither is swept by the platform's own unload
 * cleanup. The plugin's last chance to drop them is [beforePluginUnload], which the bytecode of
 * `DynamicPlugins` shows firing before `unsubscribeLazyListeners` tears this listener's own
 * registration down (and well before its `IconLoader.detachClassLoader` and the class-loader
 * liveness check). [DynamicPluginListener.pluginUnloaded] is too late to be reached.
 *
 * Each release step is guarded independently: a failure to drop one reference must not stop the
 * other from being dropped, and neither is worth failing an uninstall over.
 */
internal class PrismTabsUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId != PLUGIN_ID) return

        // Caught narrowly, and separately per step: an uninstall must not fail over a reference we
        // could not drop, but neither should this swallow an Error or a control-flow exception.
        try {
            clearIconCaches()
        } catch (e: Exception) {
            thisLogger().warn("Could not flush the icon caches on unload", e)
        }
        try {
            releaseSystemSelection()
        } catch (e: Exception) {
            thisLogger().warn("Could not release the primary selection on unload", e)
        }
    }

    /**
     * Drops the platform's derived-icon caches, one of which keys on an icon of ours.
     *
     * The retaining path, as the platform's own snapshot analysis prints it:
     *
     * ```
     * ROOT: Global JNI -> PathClassLoader -> ... -> caffeine BoundedLocalLoadingCache
     *   key: com.intellij.ui.icons.CachedImageIcon
     *   originalLoader: com.intellij.ui.icons.ImageDataByPathResourceLoader
     *   classLoader: com.intellij.ide.plugins.cl.PluginClassLoader   <- ours
     * ```
     *
     * That cache is `com.intellij.ui.icons.IconUtilKt.iconToDisabledIcon`, a
     * `LoadingCache<Icon, Icon>` the platform fills from `IconLoader.getDisabledIcon` and keys by
     * the *enabled* icon. The tool window puts one there as soon as it opens:
     * [io.prismtabs.ui.PrismTabsIcons.Hide] rides on `ToggleVisibilityAction`, which is disabled
     * whenever no group is selected, so the toolbar renders it greyed out on the very first paint.
     * [io.prismtabs.ui.PrismTabsIcons] loads its SVGs through `IconLoader.getIcon(path, class)`, so
     * each is a `CachedImageIcon` whose loader holds this plugin's class loader.
     *
     * `IconLoader.detachClassLoader`, which the platform does call, only sweeps
     * `IconLoaderKt.iconCache` (the path -> icon map) and — per its bytecode — nulls
     * `CachedImageIcon.loader` while leaving `originalLoader`, the very field on the path above,
     * set. Nothing evicts the icon from the disabled-icon cache.
     *
     * [IconLoader.clearCache] does: it fans out to every registered icon-cache cleaner, including
     * that cache's `invalidateAll`. It is the same lever the platform pulls on a theme or LaF
     * change; the caches are derived data and repopulate lazily, so a one-off flush at uninstall
     * costs a few icon re-renders and nothing else.
     */
    private fun clearIconCaches() = IconLoader.clearCache()

    /**
     * Drops the X11/Wayland *primary selection* clipboard's owner, which on Linux pins whichever
     * Swing text field the user last selected text in — including one of ours:
     *
     * ```
     * ROOT: Global JNI -> sun.awt.wl.WLDataDevice
     *   primarySelectionClipboard: sun.awt.wl.WLClipboard
     *   owner: javax.swing.text.DefaultCaret$Handler
     *   component: com.intellij.ui.components.JBTextField
     *   parent: com.intellij.openapi.ui.DialogPanel
     *   parentDisposable: com.intellij.openapi.ui.DialogWrapper$1
     *   this$0: io.prismtabs.ui.RenameGroupDialog                   <- ours
     * ```
     *
     * `DefaultCaret.updateSystemSelection` hands the caret's own `Handler` to
     * `Clipboard.setContents` as the [java.awt.datatransfer.ClipboardOwner] every time a selection
     * changes, and nothing ever takes that ownership back — the field is only overwritten by the
     * *next* `setContents`, whenever that happens to be. So a rename dialog the user selected text
     * in stays reachable long after it is disposed, and through the UI DSL panel's
     * `parentDisposable` that reaches the dialog class and its loader.
     *
     * This is not specific to [io.prismtabs.ui.RenameGroupDialog]: any text field this plugin ever
     * shows is exposed the same way, which is why the release lives here rather than in one
     * dialog's `dispose`. It is a JDK/JBR-level retention — the platform's own dialogs leak
     * identically, and only go unnoticed because their classes come from the core loader.
     *
     * Rewriting the selection with a `null` owner keeps the text (so the user's middle-click paste
     * buffer is unchanged) and drops only the reference. The `contents is StringSelection` guard is
     * an exact ownership test rather than a heuristic: `SunClipboard.getContents` returns the live
     * `Transferable` when this JVM owns the selection and a fresh `ClipboardTransferable` when
     * another application does, so a non-[StringSelection] answer means the selection is not ours
     * to rewrite and is left alone. The displaced owner's `lostOwnership` callback is benign — the
     * bytecode of `DefaultCaret$Handler` shows it clearing `ownsSelection` and, only for an
     * unfocused component, hiding the selection.
     *
     * `systemSelection` is null on Windows and macOS, where this whole retention cannot occur.
     */
    private fun releaseSystemSelection() {
        val selection = Toolkit.getDefaultToolkit().systemSelection ?: return
        val contents = selection.getContents(null)
        if (contents !is StringSelection) return

        val text = contents.getTransferData(DataFlavor.stringFlavor) as? String
        selection.setContents(StringSelection(text.orEmpty()), null)
    }

    internal companion object {
        /**
         * Must match `<id>` in plugin.xml. A drift here is silent — the listener still fires for
         * every other plugin's unload and simply never matches its own — so
         * [io.prismtabs.listener.PrismTabsUnloadListenerTest] holds the two in step.
         */
        val PLUGIN_ID: PluginId = PluginId.getId("io.prismtabs")
    }
}
