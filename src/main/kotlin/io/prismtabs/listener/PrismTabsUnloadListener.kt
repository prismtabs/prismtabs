package io.prismtabs.listener

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.IconLoader

/**
 * Lets this plugin actually unload. Without it, uninstalling (or updating, or disabling) PrismTabs
 * leaves the plugin's class loader alive, which the platform reports as "not unload-safe because
 * class loader cannot be unloaded", dumps a ~400 MB heap snapshot for, and recovers from only by
 * asking the user to restart the IDE.
 *
 * The retaining reference is an *application-level platform cache*, not anything this plugin holds:
 *
 * ```
 * ROOT: Global JNI -> PathClassLoader -> ... -> com.github.benmanes.caffeine.cache.BoundedLocalLoadingCache
 *   key: com.intellij.ui.icons.CachedImageIcon
 *   originalLoader: com.intellij.ui.icons.ImageDataByPathResourceLoader
 *   classLoader: com.intellij.ide.plugins.cl.PluginClassLoader   <- ours
 * ```
 *
 * That cache is `com.intellij.ui.icons.IconUtilKt.iconToDisabledIcon`, a `LoadingCache<Icon, Icon>`
 * the platform fills from [IconLoader.getDisabledIcon] — and it is keyed by the *enabled* icon. The
 * PrismTabs tool window puts one there as soon as it opens: [io.prismtabs.ui.PrismTabsIcons.Hide]
 * rides on `ToggleVisibilityAction`, which is disabled whenever no group is selected, so the toolbar
 * renders it greyed out on the very first paint. [PrismTabsIcons] loads both of its SVGs through
 * `IconLoader.getIcon(path, class)`, so each is a `CachedImageIcon` whose loader holds this plugin's
 * class loader — and once it is a key in an application-scoped cache, the loader outlives the plugin.
 *
 * The platform's own unload cleanup does not reach it. `DynamicPlugins` calls
 * `IconLoader.detachClassLoader(loader)`, but that only sweeps `IconLoaderKt.iconCache` (the
 * path -> icon map) and, per its bytecode, nulls `CachedImageIcon.loader` while leaving
 * `originalLoader` — the very field on the retention path above — set. Nothing evicts the icon from
 * the disabled-icon cache.
 *
 * Hence [IconLoader.clearCache], which fans out to every registered icon cache cleaner, including
 * `iconToDisabledIcon.invalidateAll()`. It is the same lever the platform pulls on a theme or LaF
 * change; the caches are derived data and repopulate lazily, so a one-off flush at uninstall costs a
 * few icon re-renders and nothing else.
 *
 * This also explains why the failure is intermittent rather than constant: that cache is built with
 * `expireAfterAccess`, so a session that never opened the tool window — or opened it long enough ago
 * that the entry had already been evicted — unloads cleanly.
 *
 * [beforePluginUnload] rather than [DynamicPluginListener.pluginUnloaded]: this listener is itself
 * registered by the plugin being unloaded, and its registration is torn down as part of that unload,
 * so `beforePluginUnload` is the last hook that still reaches plugin code.
 */
internal class PrismTabsUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId == PLUGIN_ID) {
            IconLoader.clearCache()
        }
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
