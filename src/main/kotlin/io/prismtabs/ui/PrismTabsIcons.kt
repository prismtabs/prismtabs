package io.prismtabs.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The plugin's own icons, loaded the way the platform loads its bundled ones: one call per icon,
 * against this class's loader, with the `_dark.svg` sibling picked up implicitly by theme.
 *
 * Both are here because the platform has no crossed-out eye — `AllIcons.Actions.Show` is an eye,
 * but nothing in `AllIcons` is its "hidden" counterpart (`Actions.ToggleVisibility` is an eye with
 * a table badge, `General.TbHidden` a toolbar outline, `expui/general/hide.svg` a plain dash). A
 * borrowed eye paired with a hand-drawn one would also drift apart across the classic/new UI
 * themes, where the platform's own eye changes shape and ours would not — so the pair is drawn
 * here to one geometry and stays consistent whatever the theme.
 */
internal object PrismTabsIcons {
    /** An eye: reveals a hidden group's tabs. */
    val Show: Icon = IconLoader.getIcon("/icons/showGroup.svg", PrismTabsIcons::class.java)

    /** The same eye, struck through: closes a group's tabs. */
    val Hide: Icon = IconLoader.getIcon("/icons/hideGroup.svg", PrismTabsIcons::class.java)
}
