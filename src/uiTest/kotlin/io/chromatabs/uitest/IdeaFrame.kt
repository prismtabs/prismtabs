package io.chromatabs.uitest

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

    /** The name of the [io.chromatabs.model.TabGroup] `path` is assigned to, or `null`. */
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
        // caller's (robot-server-plugin's) classloader, which can't see Chromatabs's classes —
        // IntelliJ gives every plugin its own PluginClassLoader. Loading via Chromatabs's own
        // plugin descriptor's classloader is what actually finds the class.
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.chromatabs')).getPluginClassLoader()
        const service = project.getService(java.lang.Class.forName('io.chromatabs.service.TabGroupService', true, pluginLoader))
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

    /** The [io.chromatabs.model.TabGroup.lightRgb] `path` is assigned to, or `null`. Same
     * sentinel trick as [groupNameFor] — `-1` stands in for "unassigned" since an actual
     * `lightRgb` is always masked to 24 bits ([io.chromatabs.model.TabGroupPalette]'s presets),
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
        const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.chromatabs')).getPluginClassLoader()
        const service = project.getService(java.lang.Class.forName('io.chromatabs.service.TabGroupService', true, pluginLoader))
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
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.chromatabs')).getPluginClassLoader()
            const service = project.getService(java.lang.Class.forName('io.chromatabs.service.TabGroupService', true, pluginLoader))

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
            const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.chromatabs')).getPluginClassLoader()
            const service = project.getService(java.lang.Class.forName('io.chromatabs.service.TabGroupService', true, pluginLoader))

            const groups = service.groups()
            let target = null
            for (let i = 0; i < groups.size(); i++) {
                if (groups.get(i).getName() == '$groupName') target = groups.get(i)
            }

            // TabGroupCollapse is a Kotlin `object`; its singleton is the INSTANCE static field.
            const collapseClass = java.lang.Class.forName('io.chromatabs.service.TabGroupCollapse', true, pluginLoader)
            const collapse = collapseClass.getField('INSTANCE').get(null)
            const method = collapseClass.getMethod(
                '${if (collapsed) "collapse" else "expand"}',
                com.intellij.openapi.project.Project,
                java.lang.Class.forName('io.chromatabs.service.TabGroupService', true, pluginLoader),
                java.lang.Class.forName('io.chromatabs.model.TabGroup', true, pluginLoader)
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
}

/**
 * The "Tab Groups" tool-window list rows, as rendered — colour dot aside, this is the group name,
 * member count and any "collapsed" suffix that [io.chromatabs.ui.TabGroupsToolWindow] paints. Opens
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
