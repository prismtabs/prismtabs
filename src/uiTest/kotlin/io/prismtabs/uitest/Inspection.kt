package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import java.awt.event.KeyEvent
import java.time.Duration

/**
 * Read-only views of what the IDE is *showing* — painted tab colours, row icons and text styles,
 * dialog wording, focus — for the tests that assert on rendering rather than on the model.
 *
 * Everything here reads the live component or paints it offscreen; nothing samples the desktop.
 * `java.awt.Robot.getPixelColor` would route through the xdg-desktop-portal on this machine and
 * answer for the host's Wayland session instead of the X display the sandbox is on (see README
 * "These tests need an X11 display"), whereas painting a component into a `BufferedImage` on the
 * EDT is exactly what Swing does for the screen, minus the screen.
 */

/** JS binding `project` and `service`, the project's `TabGroupService`. */
private val SERVICE_PRELUDE = """
    importPackage(com.intellij.openapi.wm.impl)
    importPackage(com.intellij.ide.plugins)
    importPackage(com.intellij.openapi.extensions)
    importPackage(com.intellij.openapi.vfs)
    importClass(com.intellij.openapi.application.ApplicationManager)
    importClass(com.intellij.openapi.application.ModalityState)

    var project = ProjectFrameHelper.getFrameHelper(component).getProject()
    var pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
    var serviceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader)
    var service = project.getService(serviceClass)
"""

/**
 * JS function `findGroupTree()`: the tool window's `PrismTabs.GroupTree`, or null while it is not
 * showing. Searched across every showing window, not just the frame, because the user may float
 * or undock the tool window into a window of its own — the same reach [toolWindowGroupTree]'s
 * XPath lookup has.
 */
internal const val GROUP_TREE_JS = """
    function findGroupTree() {
        function inside(c) {
            if (c instanceof javax.swing.JTree && c.getName() == 'PrismTabs.GroupTree') return c
            if (!(c instanceof java.awt.Container)) return null
            var n = c.getComponentCount()
            for (var i = 0; i < n; i++) { var found = inside(c.getComponent(i)); if (found != null) return found }
            return null
        }
        var windows = java.awt.Window.getWindows()
        for (var w = 0; w < windows.length; w++) {
            if (!windows[w].isShowing()) continue
            var tree = inside(windows[w])
            if (tree != null) return tree
        }
        return null
    }
"""

/** JS function `swatchRgb(icon)`: the 24-bit colour a `ColorSwatchIcon` paints, or -1 for any other icon. */
private const val SWATCH_JS = """
    function swatchRgb(icon) {
        if (icon == null || icon.getClass().getName() != 'io.prismtabs.ui.ColorSwatchIcon') return -1
        var field = icon.getClass().getDeclaredField('color')
        field.setAccessible(true)
        return field.get(icon).getRGB() & 0xFFFFFF
    }
"""

/**
 * JS function `fragments(scc)`: a `SimpleColoredComponent`'s text as `text{style}` pieces joined by
 * `¦`, where style is `bold`, `grey`, `error` or `plain` — the attributes the renderer actually
 * chose, which is what "greyed" and "bold" in a checklist mean.
 */
private const val FRAGMENTS_JS = """
    importClass(com.intellij.ui.SimpleTextAttributes)
    function fragments(scc) {
        var parts = []
        var it = scc.iterator()
        while (it.hasNext()) {
            it.next()
            var a = it.getTextAttributes()
            var style = 'plain'
            if (a.equals(SimpleTextAttributes.ERROR_ATTRIBUTES)) style = 'error'
            else if (a.equals(SimpleTextAttributes.GRAYED_ATTRIBUTES)) style = 'grey'
            else if ((a.getStyle() & SimpleTextAttributes.STYLE_BOLD) != 0) style = 'bold'
            parts.push(it.getFragment() + '{' + style + '}')
        }
        return parts.join('¦')
    }
"""

/** Whether the IDE is on a dark theme — the same `JBColor.isBright` the colour provider resolves against. */
fun IdeaFrame.isDarkTheme(): Boolean = callJs<String>("'' + !com.intellij.ui.JBColor.isBright()", true).toBoolean()

/** Which of a group's two colours the current theme shows. */
fun IdeaFrame.themed(lightRgb: Int, darkRgb: Int): Int = if (isDarkTheme()) darkRgb else lightRgb

/** The built-in preset [id]'s colour in the current theme — pair with [IdeaFrame.resetTabGroupPalette]. */
fun IdeaFrame.themedPreset(id: String): Int {
    val preset = TabGroupPalette.PRESETS.first { it.id == id }
    return themed(preset.light.rgb and 0xFFFFFF, preset.dark.rgb and 0xFFFFFF)
}

/** The [io.prismtabs.model.TabGroup.darkRgb] [path] is assigned to, or `-1`; see [IdeaFrame.groupLightRgbFor]. */
fun IdeaFrame.groupDarkRgbFor(path: String): Int = callJs(
    """
    $SERVICE_PRELUDE
    var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')
    var group = service.groupFor(file.getUrl())
    group != null ? group.getDarkRgb() : -1
    """,
    true,
)

/**
 * The colour the tab strip holds for [tabName]'s tab as 24-bit RGB, or `null` when it holds none —
 * [IdeaFrame.tabColorOf] without the alpha byte and the string sentinels.
 */
fun IdeaFrame.tabRgb(tabName: String): Int? = when (val raw = tabColorOf(tabName)) {
    "none" -> null
    "no tab" -> throw IllegalStateException("no tab named $tabName is open")
    else -> raw.toInt() and 0xFFFFFF
}

/**
 * The colour [tabName]'s tab is actually **painted** with, as 24-bit RGB: the whole tab strip is
 * painted offscreen and the dominant colour inside the tab's own bounds taken — its background,
 * since the icon, title and close button cover a small fraction of it.
 *
 * This is the reading that tells a selected tab (the group colour, at full strength) from an
 * unselected one (the platform blends it part of the way into the strip), which the colour held on
 * the `TabInfo` cannot: that is the same object either way. Measured against 2024.2's New UI Dark
 * theme with Blue's `2e5c9c`: `2e5c9c` selected, `253d5e` unselected, over a `1e1f22` strip.
 *
 * The strip's own background is left out of the count. 2024.2 fills a tab's whole bounds, but
 * 2026.2's Islands themes paint it as a rounded pill inset within them, and around a selected pill
 * the strip outnumbers the pill itself (measured: 2184 strip pixels to 1776 of Blue). The strip
 * colour is read as the dominant colour across the strip's full width at the tab's height, which the
 * few tabs these tests open never cover. It is still the answer for a tab painted no differently
 * from the strip, i.e. when nothing else covers at least [MIN_TAB_FILL] of the tab.
 */
fun IdeaFrame.paintedTabRgb(tabName: String): Int = callJs<String>(
    """
    importClass(com.intellij.openapi.fileEditor.impl.EditorTabLabel)
    function findTab(comp) {
        if (comp instanceof EditorTabLabel) {
            var ctx = comp.getAccessibleContext()
            if (ctx != null && ctx.getAccessibleName() == '$tabName') return comp
        }
        if (comp.getComponentCount) {
            var n = comp.getComponentCount()
            for (var i = 0; i < n; i++) { var found = findTab(comp.getComponent(i)); if (found != null) return found }
        }
        return null
    }
    function dominant(image, x0, x1, y0, y1, skip) {
        var counts = new java.util.HashMap()
        var best = skip
        var bestCount = 0
        for (var x = x0; x < x1; x++) {
            for (var y = y0; y < y1; y++) {
                var rgb = image.getRGB(x, y) & 0xFFFFFF
                if (rgb == skip) continue
                var count = (counts.containsKey(rgb) ? counts.get(rgb) : 0) + 1
                counts.put(rgb, count)
                if (count > bestCount) { best = rgb; bestCount = count }
            }
        }
        return [best, bestCount]
    }
    var label = findTab(component)
    if (label == null) throw new java.lang.IllegalStateException('no tab named $tabName is open')
    var tabs = label.getParent()
    var image = new java.awt.image.BufferedImage(tabs.getWidth(), tabs.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
    var g = image.createGraphics()
    try { tabs.paint(g) } finally { g.dispose() }
    var bounds = label.getBounds()
    var strip = dominant(image, 0, tabs.getWidth(), bounds.y, bounds.y + bounds.height, -1)[0]
    var tab = dominant(image, bounds.x, bounds.x + bounds.width, bounds.y, bounds.y + bounds.height, strip)
    '' + (tab[1] >= bounds.width * bounds.height * $MIN_TAB_FILL ? tab[0] : strip)
    """,
    true,
).toInt()

/**
 * The share of a tab's bounds a colour other than the strip's must cover to count as the tab's fill
 * in [paintedTabRgb]: below it are the title, icon and close button of a tab painted like the strip.
 * The smallest real fill measured is a 2026.2 Islands selected pill, at 36%.
 */
private const val MIN_TAB_FILL = 0.2

/** Whether two colours match within [tolerance] on every channel. */
fun rgbClose(expected: Int, actual: Int, tolerance: Int = 2): Boolean =
    (0..16 step 8).all { shift -> kotlin.math.abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= tolerance }

/**
 * Whether [painted] is [colour] blended part of the way into [background] — how the strip paints an
 * unselected tinted tab: the same share of [colour] on every channel, within [tolerance] levels, and
 * clearly short of both ends.
 *
 * Not an exact halfway blend, because the share is the platform's and it moved: 2024.2's New UI Dark
 * paints about 50%, 2026.2's Islands Dark 40% (measured: `8ab4f8` over `191a1c` painted as `465874`).
 */
fun isDimmedBlend(colour: Int, background: Int, painted: Int, tolerance: Int = 3): Boolean {
    val channels = (0..16 step 8).map { shift ->
        Triple(colour shr shift and 0xFF, background shr shift and 0xFF, painted shr shift and 0xFF)
    }
    // The share is read off the channel with the most room between the two colours, where one
    // level of rounding moves it least.
    val (c, b, p) = channels.maxBy { (c, b, _) -> kotlin.math.abs(c - b) }
    if (c == b) return false
    val share = (p - b).toDouble() / (c - b)
    return share in 0.25..0.75 &&
        channels.all { (c, b, p) -> kotlin.math.abs(b + share * (c - b) - p) <= tolerance }
}

fun Int.hex(): String = "%06x".format(this)

/**
 * The open popup's rows as `label=rgb`, where rgb is the colour of the row's swatch dot in the
 * current theme, or `-1` for a row without one — read off the popup's own step (`getTextFor`,
 * `getIconFor`), which is what its renderer paints each row from.
 *
 * The deepest open popup is the one read: cascading steps are separate windows, created in order.
 */
fun RemoteRobot.popupRowSwatches(): List<Pair<String, Int>> = callJs<String>(
    """
    $SWATCH_JS
    function findList(c) {
        if (c.getClass().getName().indexOf('ListPopupImpl${'$'}MyList') >= 0) return c
        if (!(c instanceof java.awt.Container)) return null
        var kids = c.getComponents()
        for (var i = 0; i < kids.length; i++) { var r = findList(kids[i]); if (r != null) return r }
        return null
    }
    var list = null
    var windows = java.awt.Window.getWindows()
    for (var w = 0; w < windows.length; w++) {
        if (!windows[w].isShowing()) continue
        var found = findList(windows[w])
        if (found != null) list = found
    }
    if (list == null) throw new java.lang.IllegalStateException('no open popup list')
    var outer = list.getClass().getDeclaredField('this${'$'}0')
    outer.setAccessible(true)
    var step = outer.get(list).getListStep()
    var values = step.getValues()
    var rows = []
    for (var i = 0; i < values.size(); i++) {
        rows.push(step.getTextFor(values.get(i)) + '=' + swatchRgb(step.getIconFor(values.get(i))))
    }
    rows.join('\n')
    """,
    true,
).lines().filter { it.isNotEmpty() }.map { it.substringBeforeLast('=') to it.substringAfterLast('=').toInt() }

/**
 * Every non-empty `JLabel`, button and text component text in the showing window titled [title] (or,
 * for an untitled popup window, in every showing window that is not the IDE frame), in component
 * order. Text components are read through their document, so an HTML message comes back as its
 * plain words.
 */
fun RemoteRobot.windowTexts(title: String? = null): List<String> = callJs<String>(
    """
    var out = []
    function walk(c) {
        if (c instanceof javax.swing.JLabel && c.getText() != null) out.push(c.getText())
        else if (c instanceof javax.swing.AbstractButton && c.getText() != null) out.push(c.getText())
        else if (c instanceof javax.swing.text.JTextComponent) {
            var doc = c.getDocument()
            out.push(doc.getText(0, doc.getLength()))
        }
        if (c instanceof java.awt.Container) {
            var kids = c.getComponents()
            for (var j = 0; j < kids.length; j++) walk(kids[j])
        }
    }
    var windows = java.awt.Window.getWindows()
    for (var i = 0; i < windows.length; i++) {
        var w = windows[i]
        if (!w.isShowing()) continue
        var wanted = ${if (title == null) "null" else "'$title'"}
        if (wanted == null ? w.getClass().getName().indexOf('IdeFrameImpl') >= 0 : !(w.getTitle && w.getTitle() == wanted)) continue
        walk(w)
    }
    out.join('\n')
    """,
    true,
).lines().map { it.trim() }.filter { it.isNotEmpty() }

/** The text of the default button of the dialog titled [title] — what Enter presses — or `null`. */
fun RemoteRobot.defaultButtonOf(title: String): String? = callJs<String>(
    """
    var result = ''
    var windows = java.awt.Window.getWindows()
    for (var i = 0; i < windows.length; i++) {
        var w = windows[i]
        if (!w.isShowing() || !(w instanceof javax.swing.JDialog) || w.getTitle() != '$title') continue
        var button = w.getRootPane().getDefaultButton()
        if (button != null) result = button.getText()
    }
    result
    """,
    true,
).ifEmpty { null }

/**
 * The keyboard focus owner as `ClassName@window title`, e.g. `JBTextField@New Tab Group` — enough
 * to say both *what* has focus and *which window* it is in.
 */
fun RemoteRobot.focusOwner(): String = callJs(
    """
    var owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
    if (owner == null) 'none'
    else {
        var window = javax.swing.SwingUtilities.getWindowAncestor(owner)
        owner.getClass().getSimpleName() + '@' + (window != null && window.getTitle ? window.getTitle() : '')
    }
    """,
    true,
)

/**
 * Closes the dialog titled [title] the way its title-bar close button does: a `WINDOW_CLOSING`
 * event, which is all the window manager's button ever delivers to AWT. Dispatched with
 * `invokeLater`, since the dialog's own close handling ends its modal loop.
 */
fun RemoteRobot.closeDialogFromTitleBar(title: String) {
    runJs(
        """
        var windows = java.awt.Window.getWindows()
        for (var i = 0; i < windows.length; i++) {
            var w = windows[i]
            if (!w.isShowing() || !(w instanceof javax.swing.JDialog) || w.getTitle() != '$title') continue
            var target = w
            javax.swing.SwingUtilities.invokeLater(new Runnable({
                run: function() { target.dispatchEvent(new java.awt.event.WindowEvent(target, java.awt.event.WindowEvent.WINDOW_CLOSING)) }
            }))
        }
        """,
        true,
    )
}

/** Selects everything in the focused text field and types [text] over it, then presses Enter. */
fun RemoteRobot.typeOverFocusedFieldAndEnter(text: String) {
    val keyboard = Keyboard(this)
    keyboard.hotKey(menuShortcutKey(), KeyEvent.VK_A)
    keyboard.enterText(text)
    keyboard.enter()
}

/**
 * The open "Files…" chooser's rows as rendered: [FRAGMENTS_JS] pieces per row, in list order —
 * `Beta.txt{plain}¦  files/alpha{grey}`.
 */
fun RemoteRobot.chooserRowFragments(): List<String> = callJs<String>(
    """
    $FRAGMENTS_JS
    function findList(c) {
        if (c instanceof com.intellij.ui.components.JBList && c.getClass().getName().indexOf('MyList') < 0) return c
        if (!(c instanceof java.awt.Container)) return null
        var kids = c.getComponents()
        for (var i = 0; i < kids.length; i++) { var r = findList(kids[i]); if (r != null) return r }
        return null
    }
    var list = null
    var windows = java.awt.Window.getWindows()
    for (var w = 0; w < windows.length; w++) {
        if (!windows[w].isShowing() || windows[w].getClass().getName().indexOf('IdeFrameImpl') >= 0) continue
        var found = findList(windows[w])
        if (found != null) list = found
    }
    if (list == null) throw new java.lang.IllegalStateException('no open chooser list')
    var model = list.getModel()
    var rows = []
    for (var i = 0; i < model.getSize(); i++) {
        var rendered = list.getCellRenderer().getListCellRendererComponent(list, model.getElementAt(i), i, false, false)
        rows.push(fragments(rendered))
    }
    rows.join('\n')
    """,
    true,
).lines().filter { it.isNotEmpty() }

/**
 * The tool window tree's visible rows as rendered: `icon|fragments`, where icon is `swatch:rrggbb`
 * for a group's colour dot, `filetype:<name>` when the row carries exactly the icon of its file's
 * type, or `other` — and fragments are [FRAGMENTS_JS] pieces.
 */
fun IdeaFrame.toolWindowRowRendering(): List<String> = callJs<String>(
    """
    $SWATCH_JS
    $FRAGMENTS_JS
    importClass(com.intellij.openapi.fileTypes.FileTypeManager)
    $GROUP_TREE_JS
    var tree = findGroupTree()
    if (tree == null) throw new java.lang.IllegalStateException('the PrismTabs tool window is not open')
    var rows = []
    for (var r = 0; r < tree.getRowCount(); r++) {
        var path = tree.getPathForRow(r)
        var node = path.getLastPathComponent()
        var rendered = tree.getCellRenderer().getTreeCellRendererComponent(
            tree, node, false, tree.isExpanded(path), tree.getModel().isLeaf(node), r, false)
        var text = fragments(rendered)
        var icon = rendered.getIcon()
        var described = 'other'
        if (swatchRgb(icon) >= 0) described = 'swatch:' + ('000000' + java.lang.Integer.toHexString(swatchRgb(icon))).slice(-6)
        else {
            var name = text.split('{')[0]
            var type = FileTypeManager.getInstance().getFileTypeByFileName(name)
            if (icon != null && icon.equals(type.getIcon())) described = 'filetype:' + type.getName()
        }
        rows.push(described + '|' + text)
    }
    rows.join('\n')
    """,
    true,
).lines().filter { it.isNotEmpty() }

/**
 * Whether [groupName]'s row in the tool window tree is expanded, read off the tree rather than
 * inferred from which rows are visible.
 */
fun IdeaFrame.isToolWindowGroupExpanded(groupName: String): Boolean = callJs<String>(
    """
    $GROUP_TREE_JS
    var tree = findGroupTree()
    if (tree == null) throw new java.lang.IllegalStateException('the PrismTabs tool window is not open')
    var expanded = false
    for (var r = 0; r < tree.getRowCount(); r++) {
        var path = tree.getPathForRow(r)
        var node = path.getLastPathComponent()
        if (path.getPathCount() == 2 && node.getUserObject().getName() == '$groupName') expanded = tree.isExpanded(path)
    }
    '' + expanded
    """,
    true,
).toBoolean()

/**
 * Invokes the action registered as [actionId] against the tool window tree's own data context — what
 * a shortcut or menu item pressed with that tree focused hands the action, so this exercises the
 * panel's `uiDataSnapshot` without depending on which keymap the sandbox runs.
 */
fun IdeaFrame.invokeActionOnToolWindowTree(actionId: String) {
    runJs(
        """
        importClass(com.intellij.ide.DataManager)
        importClass(com.intellij.openapi.actionSystem.ActionManager)
        importClass(com.intellij.openapi.actionSystem.ActionPlaces)
        importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
        $GROUP_TREE_JS
        var tree = findGroupTree()
        if (tree == null) throw new java.lang.IllegalStateException('the PrismTabs tool window is not open')
        var action = ActionManager.getInstance().getAction('$actionId')
        if (action == null) throw new java.lang.IllegalStateException('no action $actionId')
        var context = DataManager.getInstance().getDataContext(tree)
        javax.swing.SwingUtilities.invokeLater(new Runnable({
            run: function() { ActionUtil.invokeAction(action, context, ActionPlaces.UNKNOWN, null, null) }
        }))
        """,
        true,
    )
}

/** The system clipboard's text, or an empty string. */
fun RemoteRobot.clipboardText(): String = callJs(
    """
    importClass(com.intellij.openapi.ide.CopyPasteManager)
    var text = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
    text == null ? '' : '' + text
    """,
    true,
)

/** Puts [text] on the clipboard, so a later read can only match what a copy just wrote. */
fun RemoteRobot.setClipboardText(text: String) {
    runJs(
        """
        importClass(com.intellij.openapi.ide.CopyPasteManager)
        CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection('$text'))
        """,
        true,
    )
}

/**
 * The virtual files the Project view has selected, as paths relative to the project — `.` for the
 * project directory itself, which 2026.2 selects for a moment on its way to a Select in Project View
 * target.
 */
fun IdeaFrame.projectViewSelection(): List<String> = callJs<String>(
    """
    $SERVICE_PRELUDE
    importClass(com.intellij.ide.projectView.ProjectView)
    var pane = ProjectView.getInstance(project).getCurrentProjectViewPane()
    var out = []
    if (pane != null) {
        var elements = pane.getSelectedElements()
        for (var i = 0; i < elements.length; i++) {
            var e = elements[i]
            var file = (e instanceof com.intellij.psi.PsiFileSystemItem) ? e.getVirtualFile() : (e instanceof VirtualFile ? e : null)
            if (file == null) continue
            var path = file.getPath()
            var base = project.getBasePath()
            out.push(path == base ? '.' : path.substring(base.length() + 1))
        }
    }
    out.join('\n')
    """,
    true,
).lines().filter { it.isNotEmpty() }

/**
 * The Project view's tree. Matched on its class hierarchy rather than its exact class: 2024.2 shows a
 * `ProjectViewTree`, while 2026.2 shows `ProjectViewPane$MyProjectViewTree`, a subclass of it, so an
 * `@class='ProjectViewTree'` locator finds nothing there.
 */
val PROJECT_VIEW_TREE = byXpath(
    "Project view tree",
    "//div[@class='ProjectViewTree' or contains(@classhierarchy, 'com.intellij.ide.projectView.impl.ProjectViewTree ')]",
)

/** Opens the Project tool window by script and returns its tree, once it shows a row labelled [label]. */
fun IdeaFrame.projectViewTreeShowing(label: String, expandFirst: String? = null): JTreeFixture {
    runJs(
        """
        $SERVICE_PRELUDE
        var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Project')
        ApplicationManager.getApplication().invokeLater(new Runnable({ run: function() { window.activate(null) } }))
        """,
        true,
    )
    val tree = find<JTreeFixture>(PROJECT_VIEW_TREE, Duration.ofSeconds(20))
    if (expandFirst != null) {
        waitFor(Duration.ofSeconds(20), errorMessage = "no '$expandFirst' row in the project tree") {
            runCatching { tree.collectRows().any { it.trim() == expandFirst } }.getOrDefault(false)
        }
        // The root row renders as the project name followed by its location, and the fixture's
        // own name is what `expand` matches that row on.
        if (tree.collectRows().none { it.trim() == label }) tree.expand("fixture-project", expandFirst)
    }
    waitFor(Duration.ofSeconds(20), errorMessage = "no '$label' row in the project tree") {
        runCatching { tree.collectRows().any { it.trim() == label } }.getOrDefault(false)
    }
    return tree
}

/** Hides the Project tool window again, so the editor gets its width back for tab right-clicks. */
fun IdeaFrame.hideProjectView() {
    runJs(
        """
        $SERVICE_PRELUDE
        ApplicationManager.getApplication().invokeAndWait(new Runnable({
            run: function() {
                var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Project')
                if (window != null) window.hide(null)
            }
        }))
        """,
        true,
    )
}

/**
 * The items of whatever context menu is open right now, as `"text=enabled"` — empty when none is.
 * Unlike [contextMenuItemStates] this does not wait for a menu to appear, so it can assert one has gone.
 */
fun RemoteRobot.contextMenuItemStatesNow(): List<String> =
    findAll<com.intellij.remoterobot.fixtures.ComponentFixture>(byXpath("//div[@class='ActionMenuItem']"))
        .map { it.callJs("component.getText() + '=' + component.isEnabled()", true) }
