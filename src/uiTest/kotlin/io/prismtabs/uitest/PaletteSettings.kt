package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot

/**
 * The palette page's controls, for the tests that go further than [IdeaFrame.exercisePaletteConfigurable]:
 * its full layout, colour edits, and the real Settings dialog.
 */

private val CONFIGURABLE_PRELUDE = """
    importClass(com.intellij.openapi.application.ApplicationManager)
    importPackage(com.intellij.ide.plugins)
    importPackage(com.intellij.openapi.extensions)

    var pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
    var paletteService = ApplicationManager.getApplication().getService(
        java.lang.Class.forName('io.prismtabs.service.TabGroupPaletteService', true, pluginLoader))
    var textFieldClass = java.lang.Class.forName('com.intellij.ui.components.JBTextField', true, pluginLoader)
    var colorPanelClass = java.lang.Class.forName('com.intellij.ui.ColorPanel', true, pluginLoader)
    var linkClass = java.lang.Class.forName('com.intellij.ui.components.ActionLink', true, pluginLoader)

    function collect(component, cls, out) {
        if (cls.isInstance(component)) out.push(component)
        if (component.getComponentCount) {
            var n = component.getComponentCount()
            for (var i = 0; i < n; i++) collect(component.getComponent(i), cls, out)
        }
    }
    function newConfigurable() {
        var ctor = java.lang.Class.forName('io.prismtabs.ui.TabGroupPaletteConfigurable', true, pluginLoader).getDeclaredConstructor()
        ctor.setAccessible(true)
        return ctor.newInstance()
    }
    function rgb(color) { return color == null ? 'null' : '' + (color.getRGB() & 0xFFFFFF) }
"""

/**
 * The page as built for a user: its row labels, the text in each name field, each colour picker's
 * colour, and how many pickers there are — keys `labels`, `names`, `colours`, `pickers`, values
 * comma-joined, colours as decimal RGB in row order, light before dark.
 */
fun IdeaFrame.paletteConfigurableLayout(): Map<String, String> = callJs<String>(
    """
    $CONFIGURABLE_PRELUDE
    var result = new java.util.concurrent.atomic.AtomicReference('')
    ApplicationManager.getApplication().invokeAndWait(new Runnable({
        run: function() {
            var configurable = newConfigurable()
            var root = configurable.createComponent()
            configurable.reset()
            var labels = [], fields = [], pickers = []
            collect(root, java.lang.Class.forName('javax.swing.JLabel'), labels)
            collect(root, textFieldClass, fields)
            collect(root, colorPanelClass, pickers)
            var rowLabels = [], names = [], colours = []
            for (var i = 0; i < labels.length; i++) {
                var text = '' + labels[i].getText()
                if (text.length > 1 && text.charAt(text.length - 1) == ':') rowLabels.push(text)
            }
            for (var i = 0; i < fields.length; i++) {
                // Each colour picker carries a hex text field of its own; only the rows' name fields count.
                if (javax.swing.SwingUtilities.getAncestorOfClass(colorPanelClass, fields[i]) == null) names.push(fields[i].getText())
            }
            for (var i = 0; i < pickers.length; i++) colours.push(rgb(pickers[i].getSelectedColor()))
            result.set('labels=' + rowLabels.join(',') + '|names=' + names.join(',') +
                '|colours=' + colours.join(',') + '|pickers=' + pickers.length)
            configurable.disposeUIResources()
        }
    }))
    result.get()
    """,
    true,
).split('|').associate { it.substringBefore('=') to it.substringAfter('=') }

/**
 * Edits the first preset's name **and both colours** on a fresh page, applies, then clicks Restore
 * Defaults and applies again — returning the service's first preset after each apply and the page's
 * controls after the restore, as `label|light|dark` values under the keys `afterEdit`,
 * `pageAfterRestore` and `afterRestore`.
 */
fun IdeaFrame.exercisePaletteRestoreDefaultsWithColours(): Map<String, String> = callJs<String>(
    """
    $CONFIGURABLE_PRELUDE
    var result = new java.util.concurrent.atomic.AtomicReference('')
    function first() { var p = paletteService.presets().get(0); return p.getLabel() + '|' + rgb(p.getLight()) + '|' + rgb(p.getDark()) }
    ApplicationManager.getApplication().invokeAndWait(new Runnable({
        run: function() {
            var configurable = newConfigurable()
            var root = configurable.createComponent()
            configurable.reset()
            var fields = [], pickers = [], links = []
            collect(root, textFieldClass, fields)
            collect(root, colorPanelClass, pickers)
            collect(root, linkClass, links)
            var out = []

            fields[0].setText('Custom')
            pickers[0].setSelectedColor(new java.awt.Color(0x123456))
            pickers[1].setSelectedColor(new java.awt.Color(0x654321))
            configurable.apply()
            out.push('afterEdit=' + first())

            var restore = null
            for (var i = 0; i < links.length; i++) if (links[i].getText() == 'Restore Defaults') restore = links[i]
            if (restore == null) throw new java.lang.IllegalStateException('the page has no Restore Defaults link')
            restore.doClick()
            out.push('pageAfterRestore=' + fields[0].getText() + '|' + rgb(pickers[0].getSelectedColor()) + '|' + rgb(pickers[1].getSelectedColor()))
            configurable.apply()
            out.push('afterRestore=' + first())

            configurable.disposeUIResources()
            result.set(out.join('\n'))
        }
    }))
    result.get()
    """,
    true,
).lines().associate { it.substringBefore('=') to it.substringAfter('=') }

/**
 * Sets preset [index]'s two colour pickers on the palette page of the **open Settings dialog** — the
 * pickers are `ColorPanel`s, whose native chooser `remote-robot` cannot drive, so the colour is set on
 * the component itself, which is the value the page's binding reads on Apply either way.
 */
fun RemoteRobot.setPresetColoursInOpenSettings(index: Int, lightRgb: Int, darkRgb: Int) {
    runJs(
        """
        var dialog = null
        var windows = java.awt.Window.getWindows()
        for (var i = 0; i < windows.length; i++) {
            var w = windows[i]
            // "Settings" on 2024.2, "Settings – <project name>" on 2026.2; see [settingsDialog].
            var title = w instanceof javax.swing.JDialog ? '' + w.getTitle() : ''
            if (w.isShowing() && (title == 'Settings' || title.indexOf('Settings – ') == 0)) dialog = w
        }
        if (dialog == null) throw new java.lang.IllegalStateException('no Settings dialog is open')
        var pickers = []
        function collect(c) {
            if (c.getClass().getName() == 'com.intellij.ui.ColorPanel' && c.isShowing()) pickers.push(c)
            if (c instanceof java.awt.Container) { var kids = c.getComponents(); for (var j = 0; j < kids.length; j++) collect(kids[j]) }
        }
        collect(dialog)
        if (pickers.length != 16) throw new java.lang.IllegalStateException('expected 16 colour pickers on the page, found ' + pickers.length)
        pickers[${index * 2}].setSelectedColor(new java.awt.Color($lightRgb))
        pickers[${index * 2 + 1}].setSelectedColor(new java.awt.Color($darkRgb))
        """,
        true,
    )
}

/** The colours of preset [index] as the running palette service holds them: `label|light|dark`. */
fun IdeaFrame.palettePreset(index: Int): String = callJs(
    """
    $CONFIGURABLE_PRELUDE
    var p = paletteService.presets().get($index)
    p.getLabel() + '|' + rgb(p.getLight()) + '|' + rgb(p.getDark())
    """,
    true,
)
