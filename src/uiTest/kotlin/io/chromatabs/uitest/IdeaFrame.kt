package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.DefaultXpath
import com.intellij.remoterobot.search.locators.byXpath
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
}
