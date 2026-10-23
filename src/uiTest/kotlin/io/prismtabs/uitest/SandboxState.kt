package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot

/**
 * The sandbox IDE's process-wide state that the tests read or temporarily change: whether the plugin
 * can unload in place, what the platform wrote to disk, the theme and the log.
 */

private val PLUGIN_PRELUDE = """
    importPackage(com.intellij.openapi.wm.impl)
    importPackage(com.intellij.ide.plugins)
    importPackage(com.intellij.openapi.extensions)
    importClass(com.intellij.openapi.application.ApplicationManager)
    importClass(com.intellij.openapi.application.PathManager)
    importClass(com.intellij.openapi.util.JDOMUtil)
    importClass(com.intellij.util.xmlb.XmlSerializer)

    var pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
    function pluginClass(name) { return java.lang.Class.forName(name, true, pluginLoader) }
    function componentElement(path, name) {
        if (!java.nio.file.Files.exists(path)) return null
        var children = JDOMUtil.load(path).getChildren('component')
        for (var i = 0; i < children.size(); i++) {
            if (children.get(i).getAttributeValue('name') == name) return children.get(i)
        }
        return null
    }
"""

/**
 * What the platform answers when asked whether PrismTabs can be unloaded without a restart — `null` if it can.
 *
 * The reason moved between platform versions (read with `javap` from both): 2024.2's
 * `checkCanUnloadWithoutRestart(IdeaPluginDescriptorImpl)` returns it as a `String`, while 2026.2
 * turns that name into a `boolean` over `PluginMainDescriptor` and returns the reason from
 * `validateCanUnloadWithoutRestart` instead. Calling the old name on 2026.2 gives back `false`, not
 * a reason, so the newer method is preferred wherever it exists.
 */
fun IdeaFrame.unloadWithoutRestartRefusal(): String? = callJs<String>(
    """
    $PLUGIN_PRELUDE
    var descriptor = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs'))
    var plugins = DynamicPlugins.INSTANCE
    var reason = typeof plugins.validateCanUnloadWithoutRestart === 'function'
        ? plugins.validateCanUnloadWithoutRestart(descriptor)
        : plugins.checkCanUnloadWithoutRestart(descriptor)
    reason == null ? '' : '' + reason
    """,
    true,
).ifEmpty { null }

/**
 * What a restarted IDE would load for this project's tab groups: `.idea/tabGroups.xml` as the
 * platform last saved it, deserialised by the platform's `XmlSerializer` into a **fresh**
 * `TabGroupService` — a new instance, as a restart makes, so nothing held in memory by the running
 * one (the session-scoped active group above all) can leak into the answer.
 *
 * One line per group — `name|lightRgb|darkRgb|members|remembered`, member and remembered file names
 * comma-joined, members sorted and remembered in their stored order — then `active=<name or none>`.
 * Pair with [IdeaFrame.forceSaveSettings] first, which is what writes the file.
 */
fun IdeaFrame.tabGroupsAsRestartedFromDisk(): List<String> = callJs<String>(
    """
    $PLUGIN_PRELUDE
    var project = ProjectFrameHelper.getFrameHelper(component).getProject()
    var element = componentElement(java.nio.file.Paths.get(project.getBasePath(), '.idea', 'tabGroups.xml'), 'TabGroupState')
    if (element == null) throw new java.lang.IllegalStateException('no TabGroupState saved in .idea/tabGroups.xml')
    var state = XmlSerializer.deserialize(element, pluginClass('io.prismtabs.model.TabGroupState'))
    var service = pluginClass('io.prismtabs.service.TabGroupService').getConstructor().newInstance()
    service.loadState(state)
    function fileName(url) { return url.substring(url.lastIndexOf('/') + 1) }
    var out = []
    var groups = service.groups()
    for (var i = 0; i < groups.size(); i++) {
        var g = groups.get(i)
        var members = []
        var urls = service.membersOf(g.getId())
        for (var m = 0; m < urls.size(); m++) members.push(fileName(urls.get(m)))
        members.sort()
        var remembered = []
        var tabs = g.getRememberedTabUrls()
        for (var t = 0; t < tabs.size(); t++) remembered.push(fileName(tabs.get(t)))
        out.push(g.getName() + '|' + g.getLightRgb() + '|' + g.getDarkRgb() + '|' + members.join(',') + '|' + remembered.join(','))
    }
    var active = service.activeGroup()
    out.push('active=' + (active == null ? 'none' : active.getName()))
    out.join('\n')
    """,
    true,
).lines().filter { it.isNotEmpty() }

/**
 * [tabGroupsAsRestartedFromDisk]'s counterpart for the application-level palette: the options
 * directory's `prismtabsPalette.xml` loaded into a fresh `TabGroupPaletteService`, one
 * `label|lightRgb|darkRgb` line per preset. A palette with no overrides saved reads as the defaults,
 * exactly as it would after a restart.
 */
fun IdeaFrame.paletteAsRestartedFromDisk(): List<String> = callJs<String>(
    """
    $PLUGIN_PRELUDE
    var service = pluginClass('io.prismtabs.service.TabGroupPaletteService').getConstructor().newInstance()
    var element = componentElement(java.nio.file.Paths.get(PathManager.getOptionsPath(), 'prismtabsPalette.xml'), 'TabGroupPaletteState')
    if (element != null) service.loadState(XmlSerializer.deserialize(element, pluginClass('io.prismtabs.model.TabGroupPaletteState')))
    var out = []
    var presets = service.presets()
    for (var i = 0; i < presets.size(); i++) {
        var p = presets.get(i)
        out.push(p.getLabel() + '|' + (p.getLight().getRGB() & 0xFFFFFF) + '|' + (p.getDark().getRGB() & 0xFFFFFF))
    }
    out.join('\n')
    """,
    true,
).lines().filter { it.isNotEmpty() }

/**
 * Overrides preset [index] in the running palette service with [label] and both colours — the state
 * a palette edit leaves behind.
 */
fun IdeaFrame.editPalettePreset(index: Int, label: String, lightRgb: Int, darkRgb: Int) {
    runJs(
        """
        $PLUGIN_PRELUDE
        var paletteService = ApplicationManager.getApplication().getService(pluginClass('io.prismtabs.service.TabGroupPaletteService'))
        var presets = paletteService.presets()
        var edited = new java.util.ArrayList()
        for (var i = 0; i < presets.size(); i++) {
            var preset = presets.get(i)
            edited.add(i === $index
                ? preset.copy(preset.getId(), '$label', new java.awt.Color($lightRgb), new java.awt.Color($darkRgb))
                : preset)
        }
        paletteService.setPresets(edited)
        """,
        true,
    )
}

/** The id of the current UI theme, e.g. `ExperimentalDark`. */
fun RemoteRobot.currentThemeId(): String = callJs(
    "'' + com.intellij.ide.ui.LafManager.getInstance().getCurrentUIThemeLookAndFeel().getId()",
    true,
)

/**
 * Switches the IDE to the installed theme [id] and refreshes the UI, as picking it under Settings |
 * Appearance does — no restart. Always pair with a switch back in a `finally`: the theme is global
 * to the sandbox, and every later test's colour expectations are read off it.
 */
fun RemoteRobot.switchTheme(id: String) {
    runJs(
        """
        importClass(com.intellij.ide.ui.LafManager)
        var manager = LafManager.getInstance()
        var target = null
        var themes = manager.getInstalledThemes().iterator()
        while (themes.hasNext()) { var theme = themes.next(); if (theme.getId() == '$id') target = theme }
        if (target == null) throw new java.lang.IllegalStateException('no installed theme $id')
        manager.setCurrentLookAndFeel(target, false)
        manager.updateUI()
        """,
        true,
    )
}

/** The size of the sandbox's `idea.log` right now, in bytes. */
fun RemoteRobot.ideaLogLength(): Long = callJs<String>(
    """
    importClass(com.intellij.openapi.application.PathManager)
    var log = java.nio.file.Paths.get(PathManager.getLogPath(), 'idea.log')
    '' + (java.nio.file.Files.exists(log) ? java.nio.file.Files.size(log) : 0)
    """,
    true,
).toLong()

/**
 * `idea.log` from byte [offset] on — or from the start, if the log has since rotated to a file
 * shorter than [offset].
 */
fun RemoteRobot.ideaLogFrom(offset: Long): String = callJs(
    """
    importClass(com.intellij.openapi.application.PathManager)
    var log = java.nio.file.Paths.get(PathManager.getLogPath(), 'idea.log')
    var result = ''
    if (java.nio.file.Files.exists(log)) {
        var bytes = java.nio.file.Files.readAllBytes(log)
        var start = bytes.length < $offset ? 0 : $offset
        result = '' + new java.lang.String(java.util.Arrays.copyOfRange(bytes, start, bytes.length), java.nio.charset.StandardCharsets.UTF_8)
    }
    result
    """,
    true,
)

/**
 * The error entries in [log] that belong to PrismTabs, each with its trace. A log entry is a
 * timestamped line plus the untimed lines under it; `Logger.error` is written at `SEVERE` on this
 * platform (`ERROR` is accepted too).
 *
 * An entry belongs to PrismTabs when its trace runs through the plugin's code, its logger is one of
 * the plugin's (`#io.prismtabs.…`, or `#i.p.…` where the platform abbreviates it), or the platform blames the
 * plugin for it. Merely *mentioning* PrismTabs does not count: the platform appends a "Last Action:"
 * line to every error, and a PrismTabs action being the last one clicked says nothing about the cause.
 */
fun prismTabsErrors(log: String): List<String> {
    val entries = mutableListOf<StringBuilder>()
    log.lineSequence().forEach { line ->
        if (LOG_ENTRY_START.containsMatchIn(line)) entries += StringBuilder(line) else entries.lastOrNull()?.append('\n')?.append(line)
    }
    return entries.map { it.toString() }
        .filter { LOG_ERROR_LEVEL.containsMatchIn(it.lineSequence().first()) }
        .filter { entry -> PRISMTABS_OWNED.any { it.containsMatchIn(entry) } }
}

private val LOG_ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
private val LOG_ERROR_LEVEL = Regex("""\]\s+(SEVERE|ERROR) - """)
private val PRISMTABS_OWNED = listOf(
    Regex("""\bat io\.prismtabs\."""),
    Regex("""(SEVERE|ERROR) - #(io\.prismtabs|i\.p)\."""),
    Regex("""Plugin to blame: PrismTabs"""),
)
