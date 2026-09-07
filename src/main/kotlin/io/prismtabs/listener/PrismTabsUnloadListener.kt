package io.prismtabs.listener

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.IconLoader
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * Lets this plugin actually unload. Without it, uninstalling (or updating, or disabling) PrismTabs
 * leaves the plugin's class loader alive, which the platform reports as "not unload-safe because
 * class loader cannot be unloaded", dumps a ~400 MB heap snapshot for, and recovers from only by
 * asking the user to restart the IDE.
 *
 * Nothing this plugin itself holds is at fault in any of the cases below. Each is an
 * *application-global singleton owned by the platform or the JDK* that keeps a strong reference to
 * an object whose class came from this plugin's loader — and none is swept by the platform's own
 * unload cleanup. The plugin's last chance to drop them is [beforePluginUnload], which the
 * bytecode of `DynamicPlugins` shows firing before `unsubscribeLazyListeners` tears this
 * listener's own registration down (and well before its `IconLoader.detachClassLoader` and the
 * class-loader liveness check). [DynamicPluginListener.pluginUnloaded] is too late to be reached.
 *
 * Each release step is guarded independently: a failure to drop one reference must not stop the
 * others from being dropped, and none of them is worth failing an uninstall over.
 */
internal class PrismTabsUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId != PLUGIN_ID) return

        // Caught narrowly, and separately per step: an uninstall must not fail over a reference we
        // could not drop, but nor should this swallow an Error or a control-flow exception.
        try {
            clearIconCaches()
        } catch (e: Exception) {
            thisLogger().warn("Could not flush the icon caches on unload", e)
        }
        try {
            releaseClipboard(Toolkit.getDefaultToolkit().systemClipboard)
        } catch (e: Exception) {
            thisLogger().warn("Could not release the system clipboard on unload", e)
        }
        try {
            Toolkit.getDefaultToolkit().systemSelection?.let(::releaseClipboard)
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
     * Re-publishes a clipboard's contents so that nothing it holds can reach this plugin's classes.
     * Two distinct references have to go, and a clipboard can hold either.
     *
     * **The owner.** `DefaultCaret.updateSystemSelection` hands the caret's own `Handler` to
     * `Clipboard.setContents` as the [java.awt.datatransfer.ClipboardOwner] every time a text
     * selection changes, and nothing ever takes that ownership back — the field is only overwritten
     * by the *next* `setContents`, whenever that happens to be. On Linux that means the X11/Wayland
     * *primary selection* pins whichever Swing text field the user last selected text in, and
     * through it everything that field's dialog holds. The platform printed exactly that root for
     * an uninstall done after creating a group from the tab-groups popup:
     *
     * ```
     * ROOT: Global JNI -> sun.awt.wl.WLDataDevice
     *   primarySelectionClipboard: sun.awt.wl.WLClipboard
     *   owner: javax.swing.text.DefaultCaret$Handler
     *   component: javax.swing.JTextField
     *   model: javax.swing.text.PlainDocument
     *   listenerList: com.intellij.openapi.ui.Messages$InputDialog$1
     *   this$0: com.intellij.openapi.ui.Messages$InputDialog
     *   myValidator: io.prismtabs.ui.TabGroupPopupKt$promptForNameAndCreate$validator$1  <- ours
     * ```
     *
     * **The contents.** Copying out of a Swing text field — every platform in this case, not just
     * Linux — leaves a `BasicTextUI$TextTransferHandler$TextTransferable` on the system clipboard,
     * and its `c` field is the [javax.swing.text.JTextComponent] itself (`TransferHandler`'s
     * bytecode: `exportToClipboard` publishes it with a *null* owner, so here the retention is the
     * transferable, not the owner). Reproduced outside the IDE: a `JTextField` nothing else refers
     * to survives every collection after a `copy()`, and is collected the moment the clipboard is
     * re-published.
     *
     * Neither retention is specific to a dialog of ours — the text field above is the platform's
     * own [com.intellij.openapi.ui.Messages] input dialog, reaching this plugin through the
     * validator we hand it — which is why this lives here rather than in any one dialog's
     * `dispose`. The platform's own dialogs leak identically and only go unnoticed because their
     * classes come from the core loader. The IDE-side caches are *not* a third vector:
     * `ClipboardSynchronizer` caches only what it published itself (its `myCurrentContent` is
     * written in `setContent` and nowhere else), and `CopyPasteManagerWithHistory.myData` only what
     * went through `CopyPasteManager.setContents`; both hold platform-created transferables, and
     * this plugin puts nothing on either clipboard.
     *
     * **What goes back on the clipboard has to be a JDK class.** An earlier version of this method
     * republished a faithful, flavour-for-flavour copy — as a nested class of this file, which the
     * platform then reported as the whole reason the loader could not go:
     *
     * ```
     * ROOT: Global JNI -> sun.awt.wl.WLClipboard$1
     *   data: io.prismtabs.listener.PrismTabsUnloadListener$ClipboardSnapshot
     *   <class>: java.lang.Class
     * * name: com.intellij.ide.plugins.cl.PluginClassLoader
     * ```
     *
     * An object's class reaches its loader, so *any* instance of ours left on an
     * application-global singleton pins the loader just as firmly as the reference it replaced.
     * Hence [plainTextCopy]: a [StringSelection], which is `java.desktop`'s and outlives us.
     *
     * Where the contents cannot be reduced to plain text without losing something, the original
     * transferable goes back instead — that still drops the owner, and a non-text clipboard is not
     * a Swing text field's and so has nothing of ours to drop. The displaced owner's
     * `lostOwnership` callback is benign either way: the bytecode of `DefaultCaret$Handler` shows
     * it clearing `ownsSelection` and, only for an unfocused component, hiding the selection, and
     * `CopyPasteManagerWithHistory`'s clears an `myOwnContent` flag and fires the content-changed
     * event any other application's copy would.
     *
     * The identity check is an exact ownership test, and replaces a `contents is StringSelection`
     * test that read plausibly and never once fired: per `SunClipboard`'s bytecode, `setContents`
     * stores the transferable wrapped in a `TransferableProxy`, so what `getContents` returns is
     * never the `StringSelection` a caret passed in — and the whole release was a silent no-op.
     * What `getContents` does return, when this JVM owns the clipboard, is that stored field
     * itself: the same instance on every call. A clipboard owned by another application has no
     * stored contents and yields a freshly built `ClipboardTransferable` per call instead — a
     * formats-only native query, per its constructor's bytecode, so asking twice is cheap. Two
     * calls returning the same object therefore mean, and only mean, that the clipboard is ours to
     * rewrite; anything else is another application's and is left alone. Confirmed against
     * `SunClipboard`/`WLClipboard` bytecode and live under both `WLToolkit` and `XToolkit`.
     *
     * Internal rather than private so [PrismTabsUnloadListenerTest] can exercise it directly:
     * [beforePluginUnload] takes an [IdeaPluginDescriptor], which has no constructible
     * implementation outside the platform's own loading code.
     */
    internal fun releaseClipboard(clipboard: Clipboard) {
        val contents = clipboard.getContents(null) ?: return
        if (contents !== clipboard.getContents(null)) return

        clipboard.setContents(contents.plainTextCopy() ?: contents, null)
    }

    /**
     * This clipboard content as a [StringSelection] — a copy of the text and nothing else — or null
     * if the content is more than text and re-publishing it that way would lose something.
     *
     * "More than text" is read strictly: every flavour offered must be a text type or serve its
     * data as a [String]. A Swing text field's copy passes (its five flavours are three spellings
     * of `text/plain` plus the serialized-object and JVM-local-reference forms of [String]); an
     * image, a file list, or one of the IDE's own rich transferables does not, and is left alone.
     *
     * What the swap costs is one flavour spelling, and nothing another application can see. Asked
     * for the natives a Swing copy publishes against those of the equivalent [StringSelection], the
     * default [java.awt.datatransfer.SystemFlavorMap] gives the same list — `STRING`,
     * `UTF8_STRING`, `TEXT`, `text/plain` and its charset variants — bar
     * `JAVA_DATAFLAVOR:application/x-java-jvm-local-objectref`, which is by definition local to a
     * JVM that is at this moment uninstalling the plugin that put it there.
     *
     * Reading the data is cheap and safe here: [releaseClipboard] has already established that this
     * JVM owns the clipboard, so it is served from memory rather than from another application.
     */
    private fun Transferable.plainTextCopy(): StringSelection? {
        if (!isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        if (!transferDataFlavors.all { it.isText }) return null

        val text = try {
            getTransferData(DataFlavor.stringFlavor)
        } catch (e: Exception) {
            return null
        }
        return (text as? String)?.let(::StringSelection)
    }

    /** Whether this flavour is text, or hands its data over as one — see [plainTextCopy]. */
    private val DataFlavor.isText: Boolean
        get() = isFlavorTextType || representationClass == String::class.java

    internal companion object {
        /**
         * Must match `<id>` in plugin.xml. A drift here is silent — the listener still fires for
         * every other plugin's unload and simply never matches its own — so
         * [io.prismtabs.listener.PrismTabsUnloadListenerTest] holds the two in step.
         */
        val PLUGIN_ID: PluginId = PluginId.getId("io.prismtabs")
    }
}
