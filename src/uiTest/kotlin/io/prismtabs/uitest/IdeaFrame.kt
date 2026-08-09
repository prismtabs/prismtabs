package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.DefaultXpath
import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

fun RemoteRobot.idea(function: IdeaFrame.() -> Unit) {
    find<IdeaFrame>(timeout = Duration.ofSeconds(20)).apply(function)
}

fun RemoteRobot.actionMenuItem(text: String): ComponentFixture {
    val locator = byXpath("text '$text'", "//div[@class='ActionMenuItem' and @text='$text']")
    return find(ComponentFixture::class.java, locator, Duration.ofSeconds(10))
}

/**
 * The one "page object" this UI test harness needs. The Sample.kt fixture project (see
 * `src/uiTest/resources/fixture-project`) only ever has one file open, so there is exactly one
 * editor tab — no need to disambiguate between tabs by filename.
 */
@DefaultXpath("IdeFrameImpl type", "//div[@class='IdeFrameImpl']")
class IdeaFrame(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    fun openFile(path: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.vfs)
            importPackage(com.intellij.openapi.wm.impl)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')
            const openFileFunction = new Runnable({
                run: function() {
                    FileEditorManager.getInstance(project).openTextEditor(
                        new OpenFileDescriptor(project, file), true
                    )
                }
            })
            ApplicationManager.getApplication().invokeLater(openFileFunction)
            """,
            true,
        )
    }

    /** The name of the [io.prismtabs.model.TabGroup] `path` is assigned to, or `null`. */
    fun groupNameFor(path: String): String? = callJs<String>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.openapi.vfs)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')
        // Rhino's `Packages.x.y.Z` dot-navigated class reference doesn't reliably coerce to the
        // `Class<T>` overload of getService() — confirmed live ("getService(object)" argument
        // type mismatch). Class.forName(name) alone then fails too: it resolves against the
        // caller's (robot-server-plugin's) classloader, which can't see PrismTabs's classes —
        // IntelliJ gives every plugin its own PluginClassLoader. Loading via PrismTabs's own
        // plugin descriptor's classloader is what actually finds the class.
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))
        const group = service.groupFor(file.getUrl())
        // Not a plain `null`: callJs requires the script's result to already be Serializable-typed,
        // and a bare JS `null` has no type to check against ("Script must return 'Serializable' but
        // 'null' has type 'null'", confirmed live) — '' is the "unassigned" sentinel instead. Group
        // names are always non-blank (InputValidator in TabGroupPopup.promptForNameAndCreate), so
        // there's no real name this could collide with.
        group != null ? group.getName() : ''
        """,
        true,
    ).ifEmpty { null }

    /** The [io.prismtabs.model.TabGroup.lightRgb] `path` is assigned to, or `null`. Same
     * sentinel trick as [groupNameFor] — `-1` stands in for "unassigned" since an actual
     * `lightRgb` is always masked to 24 bits ([io.prismtabs.model.TabGroupPalette]'s presets),
     * so it can never collide with a real value. */
    fun groupLightRgbFor(path: String): Int = callJs<Int>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.openapi.vfs)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')

        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))
        const group = service.groupFor(file.getUrl())
        group != null ? group.getLightRgb() : -1
        """,
        true,
    )

    /**
     * Deletes every tab group and clears the active group, so a test starts from a known-empty
     * state. **Necessary, not hygiene theatre:** assignments persist to the fixture project's
     * `.idea/tabGroups.xml` and survive both the test run and an IDE restart, so without this a
     * second run of any test that creates a named group sees *two* groups with that name and
     * `popupList().findText(name)` / row assertions resolve ambiguously — diagnosed live after a
     * run reported `[CollapseMe 1 tab · collapsed, CollapseMe 1 tab]`.
     */
    fun resetTabGroups() {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))

            service.setActiveGroup(null)
            const groups = service.groups()
            const ids = []
            for (let i = 0; i < groups.size(); i++) ids.push(groups.get(i).getId())
            for (let i = 0; i < ids.length; i++) service.delete(ids[i])
            """,
            true,
        )
        closeAllEditors()
    }

    /**
     * Closes every editor tab. Part of [resetTabGroups]'s known-empty state and load-bearing for
     * anything testing *inheritance*: `FileEditorManagerListener.fileOpened` — the whole mechanism
     * behind the active group — does not fire for a file that is **already open**, so a test that
     * inherits its predecessor's open tabs silently observes no inheritance at all. Confirmed live:
     * the Phase 3 active-group test began timing out waiting for `Second.kt` to join the active
     * group only once the Phase 4 test started leaving that file open ahead of it.
     */
    fun closeAllEditors() {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importClass(com.intellij.openapi.fileEditor.ex.FileEditorManagerEx)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const task = new Runnable({
                run: function() { FileEditorManagerEx.getInstanceEx(project).closeAllFiles() }
            })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /**
     * Hides the "Tab Groups" tool window. Not cosmetic cleanup: while it is open it takes width
     * from the editor, which narrows the tab strip enough that `EditorTabLabel`s clip and a
     * `rightClick()` on one lands somewhere that never opens the context menu — confirmed live by
     * two unrelated tests starting to time out on their tab right-click as soon as a previous test
     * left this tool window showing. Any test that opens it must close it again.
     */
    fun hideTabGroupsToolWindow() {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importClass(com.intellij.openapi.wm.ToolWindowManager)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const task = new Runnable({
                run: function() {
                    const tw = ToolWindowManager.getInstance(project).getToolWindow('Tab Groups')
                    if (tw != null) tw.hide(null)
                }
            })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /** Comma-joined names of every currently open editor tab, in `FileEditorManager` order —
     * what Phase 4a's collapse/expand actually has to move. Same `''`-for-empty sentinel as
     * [groupNameFor], since `callJs` can't return a bare `null`. */
    fun openTabNames(): List<String> = callJs<String>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.openapi.fileEditor)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const files = FileEditorManager.getInstance(project).getOpenFiles()
        const names = []
        for (let i = 0; i < files.length; i++) names.push(files[i].getName())
        names.join(',')
        """,
        true,
    ).split(',').filter { it.isNotEmpty() }

    /**
     * Clears any [io.prismtabs.service.TabGroupPaletteService] customisation. Same reasoning as
     * [resetTabGroups]: overrides persist to `prismtabsPalette.xml`, and the service is
     * application-level — not scoped to the fixture project — so without this a customisation made
     * by one test leaks into every test that runs after it, in the same run or a later one.
     */
    fun resetTabGroupPalette() {
        runJs(
            """
            importClass(com.intellij.openapi.application.ApplicationManager)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)

            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const service = ApplicationManager.getApplication().getService(
                java.lang.Class.forName('io.prismtabs.service.TabGroupPaletteService', true, pluginLoader)
            )
            service.resetToDefaults()
            """,
            true,
        )
    }

    /**
     * Drives [io.prismtabs.ui.TabGroupPaletteConfigurable] the way the Settings framework does —
     * `createComponent()` then `reset()` — through direct Swing/reflection calls inside the running
     * IDE process, rather than mouse-driven navigation of the Settings tree and a native colour
     * chooser, neither of which `remote-robot` can drive reliably. This still exercises the real
     * binding machinery: `Cell.bind`/`bindText` read the live component on every `isModified`/
     * `apply` call regardless of how its value was changed, so setting `JBTextField.text` directly
     * is not a shortcut around that wiring, it is the same read path a real keystroke would hit.
     *
     * Returns each checkpoint as `"label:value"`, pipe-joined — `callJs` requires a `Serializable`
     * result, so a single joined `String` stands in for what would otherwise be several round trips.
     */
    fun exercisePaletteConfigurable(): List<String> = callJs<String>(
        """
        importClass(com.intellij.openapi.application.ApplicationManager)
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)

        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const paletteServiceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupPaletteService', true, pluginLoader)
        const paletteService = ApplicationManager.getApplication().getService(paletteServiceClass)
        const configurableClass = java.lang.Class.forName('io.prismtabs.ui.TabGroupPaletteConfigurable', true, pluginLoader)
        const textFieldClass = java.lang.Class.forName('com.intellij.ui.components.JBTextField', true, pluginLoader)
        const linkClass = java.lang.Class.forName('com.intellij.ui.components.ActionLink', true, pluginLoader)

        function collect(component, cls, out) {
            if (cls.isInstance(component)) out.push(component)
            if (component.getComponentCount) {
                const n = component.getComponentCount()
                for (let i = 0; i < n; i++) collect(component.getComponent(i), cls, out)
            }
        }

        const results = []
        const task = new Runnable({
            run: function() {
                const ctor = configurableClass.getDeclaredConstructor()
                ctor.setAccessible(true)
                const configurable = ctor.newInstance()
                const root = configurable.createComponent()
                configurable.reset()

                const textFields = []
                collect(root, textFieldClass, textFields)
                const links = []
                collect(root, linkClass, links)

                const greyField = textFields[0]
                results.push('initialLabel:' + greyField.getText())

                greyField.setText('MyGrey')
                results.push('modifiedBeforeApply:' + configurable.isModified())

                configurable.apply()
                results.push('modifiedAfterApply:' + configurable.isModified())
                results.push('serviceLabelAfterApply:' + paletteService.presets().get(0).getLabel())

                links[0].doClick()
                results.push('labelAfterRestoreDefaults:' + greyField.getText())
                results.push('modifiedAfterRestoreDefaults:' + configurable.isModified())

                configurable.apply()
                results.push('serviceLabelAfterRestoreApply:' + paletteService.presets().get(0).getLabel())

                configurable.disposeUIResources()
            }
        })
        ApplicationManager.getApplication().invokeAndWait(task)

        results.join('|')
        """,
        true,
    ).split('|')

    /**
     * Collapses or expands `groupName` through `TabGroupCollapse` — the same entry point the tool
     * window's own toggle action calls, invoked on the EDT via `invokeAndWait` because it opens and
     * closes editors. Driving the mechanism directly rather than clicking the tool window's toolbar
     * button keeps this assertion about *collapse behaviour* (which tabs close and reopen, in what
     * order) rather than about toolbar-button hit coordinates; the tool window's own rendering is
     * covered separately by [toolWindowGroupRows].
     */
    fun setGroupCollapsed(groupName: String, collapsed: Boolean) {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importClass(com.intellij.openapi.application.ApplicationManager)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))

            const groups = service.groups()
            let target = null
            for (let i = 0; i < groups.size(); i++) {
                if (groups.get(i).getName() == '$groupName') target = groups.get(i)
            }

            // TabGroupCollapse is a Kotlin `object`; its singleton is the INSTANCE static field.
            const collapseClass = java.lang.Class.forName('io.prismtabs.service.TabGroupCollapse', true, pluginLoader)
            const collapse = collapseClass.getField('INSTANCE').get(null)
            const method = collapseClass.getMethod(
                '${if (collapsed) "collapse" else "expand"}',
                com.intellij.openapi.project.Project,
                java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader),
                java.lang.Class.forName('io.prismtabs.model.TabGroup', true, pluginLoader)
            )
            method.setAccessible(true)

            const task = new Runnable({
                run: function() { method.invoke(collapse, project, service, target) }
            })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /**
     * The actual on-screen pixel colour at the centre of the tab for [path], read via
     * `Robot.getPixelColor` — ground truth for "what does a real user's eye see," with no platform
     * code between the check and the display. Deliberately not a `TabGroupService`/colour-provider
     * lookup: those only prove the *model* and *computation* are correct, not that a repaint ever
     * actually reached the screen — the exact "convincing false negative" shape README Phase 0
     * already documented for `updateFileColor` looking like it worked when it hadn't. A screenshot
     * comparison isn't enough either, confirmed live: the deep, saturated preset colours (chosen to
     * survive the unselected-tab blend) are subtle enough that a rendered screenshot thumbnail can
     * look untinted by eye even when the pixels are correct — this reads the hardware pixel directly
     * instead of trusting a human (or model) glancing at an image.
     */
    fun pixelColorAtTabCenter(path: String): String = callJs<String>(
        """
        importClass(com.intellij.openapi.fileEditor.impl.EditorTabLabel)

        function findTabLabel(comp) {
            if (comp instanceof EditorTabLabel) {
                var ctx = comp.getAccessibleContext()
                var name = ctx != null ? ctx.getAccessibleName() : null
                if (name == '$path') return comp
            }
            if (comp.getComponentCount) {
                var n = comp.getComponentCount()
                for (var i = 0; i < n; i++) {
                    var found = findTabLabel(comp.getComponent(i))
                    if (found != null) return found
                }
            }
            return null
        }

        var tabLabel = findTabLabel(component)
        var result = ''
        if (tabLabel != null && tabLabel.isShowing()) {
            var at = tabLabel.getLocationOnScreen()
            var w = tabLabel.getWidth()
            var h = tabLabel.getHeight()
            var px = at.x + Math.floor(w / 4)
            var py = at.y + Math.floor(h / 2)
            var awtRobot = new java.awt.Robot()
            var color = awtRobot.getPixelColor(px, py)
            result = color.toString() + ' at (' + px + ',' + py + ')'
        }
        result
        """,
        true,
    ).ifEmpty { "null (tab not found or not showing)" }

    /**
     * Forces every open project's and the application's persistent state to disk right now, via
     * `StoreUtil.saveDocumentsAndProjectsAndApp` (confirmed `public static final` by `javap` on
     * `app-client.jar`, 2024.2) — the same call IntelliJ's own normal shutdown path uses. Needed
     * because a *forced* process kill (simulating a restart for the manual verification pass,
     * since there's no in-process "click Restart IDE" for `remote-robot` to drive) skips the normal
     * shutdown sequence entirely: confirmed live that `TabGroupState`'s `@Storage`-backed save is
     * debounced, not synchronous per-mutation, so a bare `Stop-Process -Force` right after a flurry
     * of assignments can lose everything since the last periodic flush. A real user closing the IDE
     * normally would trigger this same save automatically; simulating that restart with a forced
     * kill needs to trigger it explicitly first.
     */
    fun forceSaveSettings() {
        runJs(
            """
            importClass(com.intellij.configurationStore.StoreUtil)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const task = new Runnable({
                run: function() { StoreUtil.saveDocumentsAndProjectsAndApp(true) }
            })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /** Clicks (selects) the tab for [path], as opposed to [io.prismtabs.uitest.openTabGroupPopupOn]'s
     * right-click — used by the manual verification pass to compare a preset's selected-vs-unselected
     * tint, matching README's Phase 0 "unselected tabs render tints at ~50% strength" finding. */
    fun selectTab(path: String) {
        find<ComponentFixture>(
            byXpath("//div[@class='EditorTabLabel' and @accessiblename='$path']"),
            Duration.ofSeconds(20),
        ).click()
    }

    /** Look-and-feel name, dark/light, screen DPI and resolution — recorded by the manual
     * verification pass alongside its screenshots, since the cross-platform checklist asks for
     * exactly this to be logged per pass. [com.intellij.ui.JBColor.isBright] is the same
     * lazily-initialised static [io.prismtabs.provider.TabGroupColorProvider] itself relies on to
     * pick light vs dark, not a reimplementation of theme detection. */
    fun environmentInfo(): List<String> = callJs<String>(
        """
        importClass(java.awt.Toolkit)
        importClass(com.intellij.ide.ui.LafManager)
        importClass(com.intellij.ui.JBColor)

        const laf = LafManager.getInstance().getCurrentLookAndFeel()
        const dpi = Toolkit.getDefaultToolkit().getScreenResolution()
        const size = Toolkit.getDefaultToolkit().getScreenSize()
        const parts = []
        parts.push('laf:' + laf.getName())
        parts.push('dark:' + !JBColor.isBright())
        parts.push('dpi:' + dpi)
        parts.push('screen:' + size.width + 'x' + size.height)
        parts.join('|')
        """,
        true,
    ).split('|')

    /**
     * Opens Settings straight to [io.prismtabs.ui.TabGroupPaletteConfigurable] via
     * `ShowSettingsUtil`, the same entry point the Settings/Preferences menu item resolves to —
     * bypassing manual tree navigation, which `remote-robot`'s UI-component layer handles poorly
     * (see README "Why remote-robot, not IDE Starter + Driver"). Fired via `invokeLater`, not
     * `invokeAndWait`: `showSettingsDialog` is itself modal, and blocking robot-server's single
     * worker thread on a call that only returns once the dialog closes would wedge the whole server
     * (see [RemoteRobotExtension]'s doc comment on exactly this failure mode).
     */
    fun openPrismTabsSettings() {
        runJs(
            """
            importClass(com.intellij.openapi.options.ShowSettingsUtil)
            importClass(com.intellij.openapi.application.ApplicationManager)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)
            importPackage(com.intellij.openapi.wm.impl)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const configurableClass = java.lang.Class.forName('io.prismtabs.ui.TabGroupPaletteConfigurable', true, pluginLoader)
            const task = new Runnable({
                run: function() { ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass) }
            })
            ApplicationManager.getApplication().invokeLater(task)
            """,
            true,
        )
    }
}

/**
 * The "Tab Groups" tool-window list rows, as rendered — colour dot aside, this is the group name,
 * member count and any "collapsed" suffix that [io.prismtabs.ui.TabGroupsToolWindow] paints. Opens
 * the tool window via its stripe button first if it isn't already showing. Unscoped
 * ([RemoteRobot.find], not [IdeaFrame]'s subtree-scoped find) for the stripe button only; the list
 * itself lives inside the frame.
 */
fun RemoteRobot.toolWindowGroupRows(): List<String> {
    val stripe = findAll<ComponentFixture>(
        byXpath("//div[@accessiblename='Tab Groups' and @class='SquareStripeButton']"),
    )
    if (findAll<JListFixture>(byXpath("//div[@class='JBList']")).isEmpty()) {
        stripe.firstOrNull()?.click()
    }
    waitFor(Duration.ofSeconds(10)) { findAll<JListFixture>(byXpath("//div[@class='JBList']")).isNotEmpty() }
    return findAll<JListFixture>(byXpath("//div[@class='JBList']")).last().collectItems()
}
