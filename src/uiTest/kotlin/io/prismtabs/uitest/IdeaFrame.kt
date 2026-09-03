package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.DefaultXpath
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import java.awt.event.KeyEvent
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
     * How many tab groups exist. Distinct from [groupNameFor], which only reports the group a
     * *file* is in: a flow can create an orphan group without assigning anything, and only a count
     * catches that.
     */
    fun groupCount(): Int = callJs<Int>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))
        service.groups().size()
        """,
        true,
    )

    /**
     * Deletes every tab group and clears the active group, so a test starts from a known-empty
     * state. **Necessary, not hygiene theatre:** assignments persist to the fixture project's
     * `.idea/tabGroups.xml` and survive both the test run and an IDE restart, so without this a
     * second run of any test that creates a named group sees *two* groups with that name and
     * `popupList().findText(name)` / row assertions resolve ambiguously — diagnosed live after a
     * run reported `[HideMe 1 file · hidden, HideMe 1 file]`.
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
     * Hides the "PrismTabs" tool window. Not cosmetic cleanup: while it is open it takes width
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
                    const tw = ToolWindowManager.getInstance(project).getToolWindow('PrismTabs')
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
     * Hides or shows `groupName` through `TabGroupVisibility` — the same entry point the tool
     * window's own toggle action calls, invoked on the EDT via `invokeAndWait` because it opens and
     * closes editors. Driving the mechanism directly rather than clicking the tool window's toolbar
     * button keeps this assertion about *hide/show behaviour* (which tabs close and reopen, in what
     * order) rather than about toolbar-button hit coordinates; the tool window's own rendering is
     * covered separately by [toolWindowGroupRows].
     */
    fun setGroupHidden(groupName: String, hidden: Boolean) {
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

            // TabGroupVisibility is a Kotlin `object`; its singleton is the INSTANCE static field.
            const visibilityClass = java.lang.Class.forName('io.prismtabs.service.TabGroupVisibility', true, pluginLoader)
            const visibility = visibilityClass.getField('INSTANCE').get(null)
            const method = visibilityClass.getMethod(
                '${if (hidden) "hide" else "show"}',
                com.intellij.openapi.project.Project,
                java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader),
                java.lang.Class.forName('io.prismtabs.model.TabGroup', true, pluginLoader)
            )
            method.setAccessible(true)

            const task = new Runnable({
                run: function() { method.invoke(visibility, project, service, target) }
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
     * `StoreUtil.saveDocumentsAndProjectsAndApp` (confirmed `public static final` by `javap`,
     * 2024.2) — the same call IntelliJ's own normal shutdown path uses. Needed because a *forced*
     * process kill (simulating a restart for the manual verification pass, since there's no
     * in-process "click Restart IDE" for `remote-robot` to drive) skips the normal shutdown
     * sequence entirely: confirmed live that `TabGroupState`'s `@Storage`-backed save is debounced,
     * not synchronous per-mutation, so a bare `Stop-Process -Force` right after a flurry of
     * assignments can lose everything since the last periodic flush. A real user closing the IDE
     * normally would trigger this same save automatically; simulating that restart with a forced
     * kill needs to trigger it explicitly first.
     *
     * **`runInEdt = false` plus an explicit `ModalityState.nonModal()` is the load-bearing part**,
     * and is not interchangeable with the obvious `runJs(..., true)` + bare `invokeAndWait(task)`
     * this used to be. `TransactionGuardImpl` only treats a *write-safe* modality as allowed, and
     * its constructor registers exactly one: `ModalityState.nonModal()` (bytecode: `nonModal()` ->
     * `myWriteSafeModalities.put(..., true)`, the map's only unconditional entry). A `remote-robot`
     * script runs under `ModalityState:{[JobProviderWithOwnerContext@...]}`, which is not it — so
     * the save reaches `FileBasedStorage.getVirtualFile` -> `refreshAndFindFileByNioFile` ->
     * `RefreshSessionImpl.<init>` -> `assertWriteSafeContext` and dies with
     * `AssertionError: Write-unsafe context!`, logged by the platform as a bare
     * `WARN - StoreUtil - Save settings failed` and **not** propagated to the caller.
     *
     * Passing the modality is only half of it: `ApplicationImpl.invokeAndWait(Runnable,
     * ModalityState)` opens with `if (isDispatchThread()) runIntendedWriteActionOnCurrentThread(r)`
     * — running inline, ignoring the argument entirely — and only reaches
     * `TransactionGuardImpl.wrapLaterInvocation(r, modality)`, the thing that actually establishes
     * the write-safe context, on the *off-EDT* branch (both confirmed by `javap -c`). So the script
     * has to start off the EDT for the requested modality to mean anything.
     *
     * Why this went unnoticed until 2026-08-19: the assert fires from the VFS *refresh* that
     * `FileBasedStorage` only needs when the storage file does not exist yet, so it aborts the save
     * precisely on the first write of `.idea/tabGroups.xml` — and any sandbox where that file
     * already existed saved cleanly and hid the bug. Measured here: with the file absent, part 1 of
     * [io.prismtabs.uitest.WindowsManualVerificationUiTest] reported PASS while writing nothing at
     * all, and part 2 then read every assignment back as `null` — indistinguishable from a genuine
     * persistence bug in the plugin, which is what makes this worth the comment. The macOS pass on
     * 2026-08-16 logged this same stack and filed it as harmless harness noise; it was not.
     */
    fun forceSaveSettings() {
        runJs(
            """
            importClass(com.intellij.configurationStore.StoreUtil)
            importClass(com.intellij.openapi.application.ApplicationManager)
            importClass(com.intellij.openapi.application.ModalityState)

            // `var` rather than `const`, matching the convention the rest of this suite's Rhino
            // scripts follow — see RemoteRobotExtension's doc for the scoping failure behind it.
            var task = new Runnable({
                run: function() { StoreUtil.saveDocumentsAndProjectsAndApp(true) }
            })
            ApplicationManager.getApplication().invokeAndWait(task, ModalityState.nonModal())
            """,
            false,
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
     * Assigns [path] to a new group named [groupName] straight through [TabGroupService.assign] —
     * the same call the popup's own swatch-click-then-name flow ends in — rather than by clicking
     * through [io.prismtabs.ui.TabGroupPopup]. Contrast [createGroupViaPopup], which drives that
     * real flow for tests whose subject *is* the popup.
     *
     * Deliberate, and not only for speed: the popup path needs `java.awt.Robot` mouse input, which
     * does not reach the IDE under Wayland (see README "These tests need an X11 display"), so a
     * paywall test built on it can't run at all on a Wayland desktop even though nothing about the
     * paywall requires a mouse. The popup's own behaviour is [TabGroupPopupUiTest]'s subject; what
     * the tests here need is simply *a group that exists*.
     */
    fun assignFileToNewGroup(path: String, groupName: String, lightRgb: Int = 0x4285F4, darkRgb: Int = 0x8AB4F8) {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.openapi.vfs)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const serviceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader)
            const service = project.getService(serviceClass)
            const groupClass = java.lang.Class.forName('io.prismtabs.model.TabGroup', true, pluginLoader)

            // The 6-arg primary constructor, named explicitly rather than taken from
            // getConstructors()[0]: a Kotlin data class with default arguments also emits a
            // synthetic DefaultConstructorMarker overload, and the array order isn't contractual.
            // Six, not seven, since `collapsed` was dropped — see TabGroupVisibility for why.
            const ctor = groupClass.getConstructor(
                java.lang.String, java.lang.String,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.util.List, java.lang.Integer.TYPE
            )
            // Integer.valueOf, not `new Integer(...)`: that constructor was removed in JDK 16 and
            // this sandbox runs 21, so Rhino would find no such constructor and throw.
            const group = ctor.newInstance(
                java.util.UUID.randomUUID().toString(), '$groupName',
                java.lang.Integer.valueOf($lightRgb), java.lang.Integer.valueOf($darkRgb),
                new java.util.ArrayList(), java.lang.Integer.valueOf(service.groups().size())
            )
            service.assign(file.getUrl(), group)
            """,
            true,
        )
    }

    /** Adds [path] to the existing group named [groupName] — [assignFileToNewGroup]'s counterpart
     * for a second member, standing in for the popup's "Add to Group…" for the same reason. */
    fun addFileToGroup(path: String, groupName: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.openapi.vfs)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))

            const groups = service.groups()
            let target = null
            for (let i = 0; i < groups.size(); i++) {
                if (groups.get(i).getName() == '$groupName') target = groups.get(i)
            }
            service.assign(file.getUrl(), target)
            """,
            true,
        )
    }

    /**
     * Assigns a file URL under the project that **no file exists at** to the group named
     * [groupName] — the one state a uiTest cannot reach by deleting something.
     *
     * Deleting a file while the IDE is running fires a VFS event, and
     * [io.prismtabs.listener.TabGroupVfsListener] answers it by clearing the assignment, so a
     * *missing member* only ever arises from a deletion that happened while the IDE was closed:
     * no event, nothing to clear, and the assignment outlives the file (see the plan's risk
     * register). Fabricating the URL is what stands in for that closed-IDE deletion; the
     * assignment it produces is byte-for-byte the one that survives it.
     */
    fun assignMissingFileToGroup(fileName: String, groupName: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
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
            service.assign('file://' + project.getBasePath() + '/$fileName', target)
            """,
            true,
        )
    }

    /**
     * Makes the running sandbox behave as an **unlicensed paid build**, which is the only way to
     * reach the paywall paths at all: `PrismTabsLicense.paidFeaturesEnabled()` is unconditionally
     * `true` while `plugin.xml` has no `<product-descriptor>` (see that function's doc, and the
     * README's "Marketplace publishing prep" for why the tag isn't added yet).
     *
     * Simulated entirely from the test side — no test-only seam in production code — by supplying
     * the two inputs the real gate reads:
     *  - `IdeaPluginDescriptorImpl.productCode`, the field behind `PluginDescriptor.getProductCode()`
     *    that the `<product-descriptor code="">` tag populates. `private final`, so it's written
     *    reflectively; `setAccessible(true)` permits that for a non-static final instance field.
     *  - `LicensingFacade.INSTANCE`, installed via its own `public static setInstance` with an empty
     *    `confirmationStamps` map — exactly what a real IDE holds for a paid plugin the user has no
     *    licence for, so `isLicensed()` returns `false` through its genuine code path rather than a
     *    stubbed one.
     *
     * The reverse ([restoreUnpaidBuild]) is not optional: both mutations are global to the IDE
     * process and would otherwise leave every later test running against a locked plugin.
     */
    fun simulateUnlicensedPaidBuild() {
        runJs(
            """
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)
            importClass(com.intellij.ui.LicensingFacade)

            // Resolved exactly as PrismTabsLicense.isPaidBuild does — getPluginByClass on that very
            // class — not by plugin id: were the two ever to return different descriptor objects,
            // setting the field on the wrong one would leave the gate open and quietly turn every
            // locked-path assertion into a test of the unlocked path.
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const licenseClass = java.lang.Class.forName('io.prismtabs.license.PrismTabsLicense', true, pluginLoader)
            const descriptor = PluginManager.getPluginByClass(licenseClass)
            const field = descriptor.getClass().getDeclaredField('productCode')
            field.setAccessible(true)
            field.set(descriptor, 'PPRISMTABS')

            // A real facade object, not a mock: getConfirmationStamp(code) reads this very map, so an
            // empty one is precisely "no licence for PPRISMTABS" as the platform would represent it.
            const facade = new LicensingFacade()
            facade.confirmationStamps = new java.util.HashMap()
            LicensingFacade.setInstance(facade)
            """,
            true,
        )
    }

    /** Undoes [simulateUnlicensedPaidBuild]. Must run in a `finally` — see its doc. */
    fun restoreUnpaidBuild() {
        runJs(
            """
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)
            importClass(com.intellij.ui.LicensingFacade)

            // Resolved identically to simulateUnlicensedPaidBuild — see the note there. An
            // asymmetric lookup here would clear the field on a different descriptor than the one it
            // was set on, leaving the gate permanently armed for every later test with nothing to
            // fail: LicensingFacade.setInstance(null) alone makes isLicensed() null, which reads as
            // licensed, so the leak would be invisible.
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const licenseClass = java.lang.Class.forName('io.prismtabs.license.PrismTabsLicense', true, pluginLoader)
            const descriptor = PluginManager.getPluginByClass(licenseClass)
            const field = descriptor.getClass().getDeclaredField('productCode')
            field.setAccessible(true)
            field.set(descriptor, null)

            LicensingFacade.setInstance(null)
            """,
            true,
        )
    }

    /** The real gate, read over the wire — asserted in each paywall test so a simulation that
     * silently stopped working (a renamed field, say) fails loudly there instead of quietly turning
     * every locked-path assertion into a test of the unlocked path. */
    fun paidFeaturesEnabled(): Boolean = callJs<Boolean>(
        """
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)

        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const licenseClass = java.lang.Class.forName('io.prismtabs.license.PrismTabsLicense', true, pluginLoader)
        const license = licenseClass.getField('INSTANCE').get(null)
        licenseClass.getMethod('paidFeaturesEnabled').invoke(license)
        """,
        true,
    )

    /**
     * Unregisters the IDE's registration actions for the duration of a test, so a gated action's
     * `PrismTabsLicense.requestLicense` upsell finds nothing to open and returns silently.
     *
     * Not squeamishness about an extra window: `RegisterPlugins` is registered by the bundled
     * marketplace plugin (confirmed present in the 2024.2 CE jars), and a modal `DialogWrapper` left
     * open on the sandbox is the exact failure this repo has already been bitten by — see
     * [openPrismTabsSettings] on why a modal must never be opened from a robot-server script. The
     * upsell dialog itself is not what these tests are asserting; that the gated action *didn't
     * happen* is.
     */
    fun suppressRegisterDialog() {
        runJs(
            """
            importClass(com.intellij.openapi.actionSystem.ActionManager)
            importClass(com.intellij.openapi.application.ApplicationManager)
            importClass(com.intellij.openapi.util.Key)

            const actionManager = ActionManager.getInstance()
            // unregisterAction discards the instance, so the removed actions are stashed on the
            // Application for [restoreRegisterDialog] to put back. Key.create every call would mint
            // a *new* key each time (Key equality is identity), so the stash would be unreadable —
            // findKeyByName re-finds the one this script created the first time round.
            const keyName = 'prismtabs.uitest.suppressedRegisterActions'
            let key = Key.findKeyByName(keyName)
            if (key == null) key = Key.create(keyName)

            const application = ApplicationManager.getApplication()
            // Merged into any existing stash, never replacing it: on a second call every id is
            // already unregistered, so a fresh map would come back empty and overwrite the real
            // one — leaving restoreRegisterDialog nothing to put back and the actions gone for good.
            let stash = application.getUserData(key)
            if (stash == null) stash = new java.util.HashMap()
            const ids = ['RegisterPlugins', 'Register']
            for (var i = 0; i < ids.length; i++) {
                const action = actionManager.getAction(ids[i])
                if (action == null) continue
                stash.put(ids[i], action)
                actionManager.unregisterAction(ids[i])
            }
            application.putUserData(key, stash)
            """,
            true,
        )
    }

    /**
     * Re-registers whatever [suppressRegisterDialog] removed. Must run in a `finally`: `ActionManager`
     * is application-global, so a missed restore would silently strip the registration actions from
     * every later test in the same sandbox session — and worse, from any *manual* poke at that IDE
     * afterwards.
     */
    fun restoreRegisterDialog() {
        runJs(
            """
            importClass(com.intellij.openapi.actionSystem.ActionManager)
            importClass(com.intellij.openapi.application.ApplicationManager)
            importClass(com.intellij.openapi.util.Key)

            const key = Key.findKeyByName('prismtabs.uitest.suppressedRegisterActions')
            if (key != null) {
                const application = ApplicationManager.getApplication()
                const stash = application.getUserData(key)
                if (stash != null) {
                    const actionManager = ActionManager.getInstance()
                    const entries = stash.entrySet().iterator()
                    while (entries.hasNext()) {
                        const entry = entries.next()
                        // Re-registering an id that is somehow already back would throw; the guard
                        // keeps a double restore (finally after an early failure) harmless.
                        if (actionManager.getAction(entry.getKey()) == null) {
                            actionManager.registerAction(entry.getKey(), entry.getValue())
                        }
                    }
                    application.putUserData(key, null)
                }
            }
            """,
            true,
        )
    }

    /**
     * The "PrismTabs" tool window's rendered empty-state text — the locked panel's
     * [com.intellij.ui.components.JBPanelWithEmptyText] message, its every appended line joined by
     * `|`. Empty when the tool window is showing the real group list instead, which is what makes
     * this a usable "is it locked?" assertion in both directions.
     *
     * Reads `StatusText.getText()` plus `getWrappedFragmentsIterable()`: the primary line and the
     * appended ones are separate components, so neither alone sees the whole message.
     */
    fun toolWindowEmptyText(): String = callJs<String>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importClass(com.intellij.openapi.wm.ToolWindowManager)
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.ui.components.JBPanelWithEmptyText)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()

        const result = new java.util.concurrent.atomic.AtomicReference('')
        const task = new Runnable({
            run: function() {
                const tw = ToolWindowManager.getInstance(project).getToolWindow('PrismTabs')
                if (tw == null) return
                const cm = tw.getContentManagerIfCreated()
                if (cm == null || cm.getContentCount() == 0) return

                // Matched on Component.name, not on type: the tool window hierarchy also holds the
                // platform's own JBPanelWithEmptyText instances (empty text "Nothing to show"), and
                // a by-type search returns one of those. The name is set in TabGroupsToolWindow.kt
                // (LOCKED_PANEL_NAME) — keep the two literals in step.
                const panels = []
                function collect(comp) {
                    if (comp instanceof JBPanelWithEmptyText && comp.getName() == 'PrismTabs.LockedToolWindow') {
                        panels.push(comp)
                    }
                    if (comp.getComponentCount) {
                        const n = comp.getComponentCount()
                        for (var i = 0; i < n; i++) collect(comp.getComponent(i))
                    }
                }
                collect(cm.getContent(0).getComponent())
                if (panels.length == 0) return

                const status = panels[0].getEmptyText()
                const parts = []
                parts.push(status.getText())
                const wrapped = status.getWrappedFragmentsIterable().iterator()
                while (wrapped.hasNext()) parts.push(String(wrapped.next().toString()))
                result.set(parts.join('|'))
            }
        })
        ApplicationManager.getApplication().invokeAndWait(task)
        result.get()
        """,
        true,
    )

    /**
     * Shows the "PrismTabs" tool window via `ToolWindowManager`, not by clicking its stripe button
     * as [toolWindowGroupRows] does. Two reasons: a locked tool window has no `JBList` for that
     * helper's locator to wait for, and `ToolWindow.show()` is what fires `toolWindowShown` — the
     * listener [io.prismtabs.ui.TabGroupsToolWindowFactory]'s panel re-reads the licence on, so this
     * exercises the real mid-session lock/unlock path rather than sidestepping it.
     */
    fun showTabGroupsToolWindow() {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importClass(com.intellij.openapi.wm.ToolWindowManager)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const task = new Runnable({
                run: function() {
                    const tw = ToolWindowManager.getInstance(project).getToolWindow('PrismTabs')
                    if (tw != null) tw.show(null)
                }
            })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /** Invokes the locked tool window's "reopen the tabs of N hidden groups" escape hatch —
     * [io.prismtabs.ui.showAllHiddenGroups], the same function that link calls. Driven directly
     * rather than clicked for the reason given on [setGroupHidden]. */
    fun showAllHiddenGroups() {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const serviceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader)
            const service = project.getService(serviceClass)

            // Top-level Kotlin functions compile to static methods on a <FileName>Kt facade class.
            const facade = java.lang.Class.forName('io.prismtabs.ui.TabGroupsToolWindowKt', true, pluginLoader)
            const method = facade.getDeclaredMethod('showAllHiddenGroups', com.intellij.openapi.project.Project, serviceClass)
            method.setAccessible(true)

            const task = new Runnable({ run: function() { method.invoke(null, project, service) } })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /**
     * Invokes the tool window's *gated* hide/show choke point — the private top-level
     * `toggleVisibility(project, service, group)` in `TabGroupsToolWindow.kt` that its toolbar
     * button, its context menu item and its double-click shortcut all land on, and which carries the
     * `requirePaidFeature` check itself.
     *
     * Deliberately **not** [setGroupHidden]: that one calls `TabGroupVisibility` directly and so
     * runs *past* the gate, which is right for tests about hide/show behaviour and useless for a test
     * about the paywall. This is the only helper that exercises the gate on that call site, so
     * without it the check at `TabGroupsToolWindow.kt`'s `toggleVisibility` could be deleted outright
     * and the whole suite would still pass — every other path into hiding either bypasses it
     * ([setGroupHidden]) or is the deliberately-free escape hatch ([showAllHiddenGroups]).
     *
     * Reached reflectively because the function is `private`; the compiled signature was confirmed
     * with `javap -p` rather than assumed, per CLAUDE.md. Pair it with [suppressRegisterDialog]: a
     * refused call raises the registration dialog, and a modal left open wedges robot-server.
     */
    fun invokeToggleVisibility(groupName: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const serviceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader)
            const service = project.getService(serviceClass)
            const groupClass = java.lang.Class.forName('io.prismtabs.model.TabGroup', true, pluginLoader)

            const groups = service.groups()
            let target = null
            for (let i = 0; i < groups.size(); i++) {
                if (groups.get(i).getName() == '$groupName') target = groups.get(i)
            }
            // Named explicitly rather than left to the NPE Kotlin's own null check would raise
            // inside the invoked function, which surfaces as an opaque IdeaSideException.
            if (target == null) {
                throw new java.lang.IllegalStateException('no tab group named $groupName')
            }

            const facade = java.lang.Class.forName('io.prismtabs.ui.TabGroupsToolWindowKt', true, pluginLoader)
            const method = facade.getDeclaredMethod(
                'toggleVisibility', com.intellij.openapi.project.Project, serviceClass, groupClass
            )
            method.setAccessible(true)

            const task = new Runnable({ run: function() { method.invoke(null, project, service, target) } })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /**
     * Whether the group named [groupName] reads as hidden — [io.prismtabs.service.TabGroupVisibility]'s
     * own derivation, asked of the live `FileEditorManager` rather than of a stored flag, because
     * there is no stored flag: see that object's doc.
     *
     * The companion assertion to [openTabNames] rather than a replacement for it: tabs staying open
     * is what the *user* notices when a gated hide is refused, and asking the same question the tool
     * window's own toggle asks is what pins down what the row would say about them.
     */
    fun isGroupHidden(groupName: String): Boolean = callJs<Boolean>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const serviceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader)
        const service = project.getService(serviceClass)

        const groups = service.groups()
        let target = null
        for (let i = 0; i < groups.size(); i++) {
            if (groups.get(i).getName() == '$groupName') target = groups.get(i)
        }
        // Throws rather than answering `false` for a group that isn't there: every caller asserts on
        // this in the negative ("must not have been hidden"), so a silent `false` would turn a
        // vanished or misspelt group into a passing paywall test.
        if (target == null) {
            throw new java.lang.IllegalStateException('no tab group named $groupName')
        }

        const visibilityClass = java.lang.Class.forName('io.prismtabs.service.TabGroupVisibility', true, pluginLoader)
        const visibility = visibilityClass.getField('INSTANCE').get(null)
        const method = visibilityClass.getMethod(
            'isHidden',
            com.intellij.openapi.project.Project,
            serviceClass,
            java.lang.Class.forName('io.prismtabs.model.TabGroup', true, pluginLoader)
        )
        method.setAccessible(true)
        // `.booleanValue()` rather than the bare `invoke`: reflection hands back a boxed
        // `java.lang.Boolean`, which the script engine wraps as a Java object rather than a JS
        // boolean, and the wrapper does not survive the trip back over the wire.
        method.invoke(visibility, project, service, target).booleanValue()
        """,
        true,
    )

    /**
     * Invokes [io.prismtabs.ui.toggleActiveGroup] on the group named [groupName] — the same choke
     * point the popup's "Mark as Active Group" / "Clear Active Group" item and the tool window's
     * "Active Group" toggle both call, so this covers the gate without mouse-driving a popup (which on a locked build would also fire the
     * registration dialog — see [suppressRegisterDialog]).
     */
    fun toggleActiveGroup(groupName: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.ide.plugins)
            importPackage(com.intellij.openapi.extensions)
            importClass(com.intellij.openapi.application.ApplicationManager)

            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            const project = frameHelper.getProject()
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
            const serviceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader)
            const service = project.getService(serviceClass)
            const groupClass = java.lang.Class.forName('io.prismtabs.model.TabGroup', true, pluginLoader)

            const groups = service.groups()
            let target = null
            for (let i = 0; i < groups.size(); i++) {
                if (groups.get(i).getName() == '$groupName') target = groups.get(i)
            }

            const facade = java.lang.Class.forName('io.prismtabs.ui.TabGroupPopupKt', true, pluginLoader)
            const method = facade.getDeclaredMethod(
                'toggleActiveGroup', com.intellij.openapi.project.Project, groupClass, serviceClass
            )
            method.setAccessible(true)

            const task = new Runnable({ run: function() { method.invoke(null, project, target, service) } })
            ApplicationManager.getApplication().invokeAndWait(task)
            """,
            true,
        )
    }

    /** The name of the currently active ("sticky") group, or `null`. Same `''`-for-null sentinel as
     * [groupNameFor]. */
    fun activeGroupName(): String? = callJs<String>(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))
        const group = service.activeGroup()
        group != null ? group.getName() : ''
        """,
        true,
    ).ifEmpty { null }

    /**
     * [exercisePaletteConfigurable]'s locked counterpart: drives the same Configurable the same way,
     * but asserts the paywall — that the bound editing controls come up disabled, and that an
     * `apply()` forced past them (a licence lapsing mid-dialog is the real-world route) discards the
     * edit rather than persisting it, leaving the page settled rather than permanently modified.
     */
    fun exercisePaletteConfigurableLocked(): List<String> = callJs<String>(
        """
        importClass(com.intellij.openapi.application.ApplicationManager)
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)

        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        const paletteServiceClass = java.lang.Class.forName('io.prismtabs.service.TabGroupPaletteService', true, pluginLoader)
        const paletteService = ApplicationManager.getApplication().getService(paletteServiceClass)
        const configurableClass = java.lang.Class.forName('io.prismtabs.ui.TabGroupPaletteConfigurable', true, pluginLoader)
        const textFieldClass = java.lang.Class.forName('com.intellij.ui.components.JBTextField', true, pluginLoader)

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
                const greyField = textFields[0]

                results.push('fieldEnabled:' + greyField.isEnabled())

                // Forced past the disabled control on purpose: setText works regardless of enabled
                // state, which is exactly the "licence lapsed while this dialog was open" shape.
                greyField.setText('PaywallBypass')
                configurable.apply()
                results.push('serviceLabelAfterApply:' + paletteService.presets().get(0).getLabel())
                results.push('modifiedAfterApply:' + configurable.isModified())

                configurable.disposeUIResources()
            }
        })
        ApplicationManager.getApplication().invokeAndWait(task)

        results.join('|')
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
 * The "PrismTabs" tool-window tree itself, for the tests that need more of it than
 * [toolWindowGroupRows]' rendered rows — its live selection, or a right-click on one row. Opens the
 * tool window first if it isn't already showing, exactly as [toolWindowGroupRows] does.
 */
fun RemoteRobot.toolWindowGroupTree(): JTreeFixture {
    toolWindowGroupRows()
    return find(byXpath(GROUP_TREE_XPATH))
}

/**
 * Expands [groupName]'s row, if it isn't already, and returns the tree's visible rows once its
 * children are showing. Only meaningful for a group that *has* members — an empty one never grows
 * children and would simply time out here.
 *
 * Driven as a user would: select the row, then press Right — the tree's own expand key. Not
 * `JTreeFixture.expand`, whose path matching is against the *rendered* row text, which here carries
 * the member count and the "hidden"/"active" suffixes and so is not a stable path segment. Not a
 * double-click either: on a group row that is Hide Group, which closes the very tabs the test is
 * about (see `TabGroupsToolWindow`'s `toggleClickCount = 0`).
 */
fun RemoteRobot.expandToolWindowGroup(groupName: String): List<String> {
    val tree = toolWindowGroupTree()
    if (memberRowsIn(tree.collectRows(), groupName).isNotEmpty()) return tree.collectRows()
    tree.clickRowWithText(groupName, false)
    Keyboard(this).key(KeyEvent.VK_RIGHT)
    waitFor(Duration.ofSeconds(10)) { memberRowsIn(tree.collectRows(), groupName).isNotEmpty() }
    return tree.collectRows()
}

/**
 * The rendered text of every selected row, flattened out of `collectSelectedPaths`' root-to-node
 * paths — the tree's answer to the list fixture's `collectSelectedItems`, which this window's
 * assertions used before Phase 6a made it a tree.
 */
fun RemoteRobot.selectedToolWindowRows(): List<String> = toolWindowGroupTree().collectSelectedPaths().flatten()

/**
 * The rows under [groupName] once it is expanded — the group's member files, as rendered. Sliced
 * out of the whole tree rather than read off the node, because a rendered row is what this phase is
 * actually about: name, greyed directory, and any "missing" suffix.
 */
fun RemoteRobot.toolWindowMemberRows(groupName: String): List<String> =
    memberRowsIn(expandToolWindowGroup(groupName), groupName)

/**
 * [groupName]'s children within [rows]: everything after its row up to the next *group* row, or the
 * end of the tree.
 */
private fun memberRowsIn(rows: List<String>, groupName: String): List<String> {
    val start = rows.indexOfFirst { it.startsWith(groupName) }
    if (start < 0) return emptyList()
    return rows.drop(start + 1).takeWhile { !isGroupRow(it) }
}

/** Group rows carry the "N files" count this plugin paints; member rows never do. */
private fun isGroupRow(row: String): Boolean = Regex("""\s\d+ files?(\s|$)""").containsMatchIn(row)

/**
 * Every item of the context menu that is open right now, as `"text=enabled"` — the reading that
 * makes a greyed-out menu assertable. A menu whose items are all `=false` is what a right-click on
 * a list with no selection used to produce; see `installRowSelectingPopupMenu`.
 */
fun RemoteRobot.contextMenuItemStates(): List<String> {
    val locator = byXpath("//div[@class='ActionMenuItem']")
    waitFor(Duration.ofSeconds(10)) { findAll<ComponentFixture>(locator).isNotEmpty() }
    return findAll<ComponentFixture>(locator).map {
        it.callJs("component.getText() + '=' + component.isEnabled()", true)
    }
}

/**
 * The "PrismTabs" tool-window tree's visible rows, as rendered — colour dot aside, a group row is
 * its name, member count and any "hidden"/"active" suffix, and (once expanded) a member row is its
 * file name plus greyed directory. Opens the tool window via its stripe button first if it isn't
 * already showing. Unscoped ([RemoteRobot.find], not [IdeaFrame]'s subtree-scoped find) for the
 * stripe button only; the tree itself lives inside the frame.
 */
fun RemoteRobot.toolWindowGroupRows(): List<String> {
    val stripe = findAll<ComponentFixture>(
        byXpath("//div[@accessiblename='PrismTabs' and @class='SquareStripeButton']"),
    )
    if (findAll<JTreeFixture>(byXpath(GROUP_TREE_XPATH)).isEmpty()) {
        stripe.firstOrNull()?.click()
    }
    waitFor(Duration.ofSeconds(10)) { findAll<JTreeFixture>(byXpath(GROUP_TREE_XPATH)).isNotEmpty() }
    return find<JTreeFixture>(byXpath(GROUP_TREE_XPATH)).collectRows()
}

/**
 * Locates the tool window's group tree by the `Component.name` the panel sets on it
 * (`io.prismtabs.ui.GROUP_TREE_NAME`) rather than by type: the IDE frame holds several other trees,
 * the Project view's among them, and a by-type locator is a coin toss between them. Keep the
 * literal in step with the source constant.
 */
private const val GROUP_TREE_XPATH = "//div[@name='PrismTabs.GroupTree']"
