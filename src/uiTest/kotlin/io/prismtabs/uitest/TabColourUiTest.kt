package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * The tab colour PrismTabs supplies, against the other things that decide what a tab looks like: the
 * platform's own File Colors, and the theme.
 */
@ExtendWith(RemoteRobotExtension::class)
class TabColourUiTest {
    init {
        StepsLogger.init()
    }

    /**
     * `order="first"` in plugin.xml, observed rather than read: a file outside the project already
     * wears the platform's "Non-Project Files" colour — asserted first, so the test cannot pass on a
     * file that had no competing colour — and once it is put in a group, the group's colour is what
     * the tab shows.
     */
    @Test
    fun `a group colour wins over the platform's File Colors`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            var path: String? = null
            try {
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                path = createFileOutsideProject("Outside.txt")
                openAbsoluteFile(path)

                val platformColour = platformFileColourOf(path)
                assertTrue(platformColour != null, "precondition: the platform gives a non-project file a File Colors colour")
                waitFor(ofSeconds(10), errorMessage = "the tab never took the platform's colour: ${tabRgb("Outside.txt")?.hex()}") {
                    runCatching { tabRgb("Outside.txt") == platformColour }.getOrDefault(false)
                }

                remoteRobot.openTabGroupPopupOn(this, "Outside.txt")
                remoteRobot.clickPopupItem("Blue")
                dialog("New Tab Group") { find<JButtonFixture>(JButtonFixture.byText("OK")).click() }

                val blue = themedPreset("blue")
                waitFor(ofSeconds(10), errorMessage = "the tab kept the platform's colour: ${tabRgb("Outside.txt")?.hex()}") {
                    tabRgb("Outside.txt") == blue
                }
                waitFor(ofSeconds(10)) { rgbClose(blue, paintedTabRgb("Outside.txt")) }
            } finally {
                closeAllEditors()
                resetTabGroups()
                path?.let { deleteFileOutsideProject(it) }
            }
        }
    }

    /**
     * The group holds a light and a dark colour and the tab resolves between them as it paints, so a
     * theme switch retints it at once, in both directions, with no restart and no stale colour.
     */
    @Test
    fun `switching theme retints a coloured tab straight away, both ways`(remoteRobot: RemoteRobot) {
        val original = remoteRobot.currentThemeId()
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                openFile("Second.kt")
                openFile("Sample.kt")
                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Themed")
                val blue = TabGroupPalette.PRESETS.first { it.id == "blue" }
                val light = blue.light.rgb and 0xFFFFFF
                val dark = blue.dark.rgb and 0xFFFFFF

                val startedDark = isDarkTheme()
                waitFor(ofSeconds(10)) { rgbClose(if (startedDark) dark else light, paintedTabRgb("Sample.kt")) }

                remoteRobot.switchTheme(if (startedDark) "ExperimentalLight" else "ExperimentalDark")
                waitFor(ofSeconds(30)) { isDarkTheme() != startedDark }
                waitFor(ofSeconds(10), errorMessage = "after the switch the tab painted ${paintedTabRgb("Sample.kt").hex()}") {
                    rgbClose(if (startedDark) light else dark, paintedTabRgb("Sample.kt"))
                }

                remoteRobot.switchTheme(original)
                waitFor(ofSeconds(30)) { isDarkTheme() == startedDark }
                waitFor(ofSeconds(10), errorMessage = "after switching back the tab painted ${paintedTabRgb("Sample.kt").hex()}") {
                    rgbClose(if (startedDark) dark else light, paintedTabRgb("Sample.kt"))
                }
                assertEquals("Themed", groupNameFor("Sample.kt"))
            } finally {
                if (remoteRobot.currentThemeId() != original) remoteRobot.switchTheme(original)
            }
        }
    }
}

/**
 * Creates [name] in a fresh temporary directory outside the project, and returns its absolute path.
 *
 * The VFS refresh that makes the file visible runs off the EDT under `ModalityState.nonModal()`, for
 * the reason [createFixtureFile] gives: from an EDT robot script it is logged as a write-unsafe SEVERE.
 */
private fun IdeaFrame.createFileOutsideProject(name: String): String = callJs(
    """
    importClass(com.intellij.openapi.application.ApplicationManager)
    importClass(com.intellij.openapi.application.ModalityState)
    var directory = java.nio.file.Files.createTempDirectory('prismtabs-uitest')
    var file = directory.resolve('$name')
    java.nio.file.Files.writeString(file, 'outside the project\n')
    ApplicationManager.getApplication().invokeAndWait(new Runnable({
        run: function() { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) }
    }), ModalityState.nonModal())
    '' + file.toString()
    """,
    false,
)

private fun IdeaFrame.openAbsoluteFile(path: String) {
    runJs(
        """
        importClass(com.intellij.openapi.application.ApplicationManager)
        var project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject()
        var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath('$path')
        ApplicationManager.getApplication().invokeAndWait(new Runnable({
            run: function() { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true) }
        }))
        """,
        true,
    )
}

/** The colour File Colors gives the file at [path], as 24-bit RGB, or `null` for none. */
private fun IdeaFrame.platformFileColourOf(path: String): Int? = callJs<String>(
    """
    var project = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component).getProject()
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath('$path')
    var colour = com.intellij.ui.FileColorManager.getInstance(project).getFileColor(file)
    colour == null ? '' : '' + (colour.getRGB() & 0xFFFFFF)
    """,
    true,
).ifEmpty { null }?.toInt()

/** Deletes the file [createFileOutsideProject] made, and its directory, through the VFS in a write action. */
private fun IdeaFrame.deleteFileOutsideProject(path: String) {
    runJs(
        """
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.openapi.application.ModalityState)
        var app = ApplicationManager.getApplication()
        app.invokeAndWait(new Runnable({
            run: function() {
                app.runWriteAction(new Runnable({
                    run: function() {
                        var directory = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .refreshAndFindFileByNioFile(java.nio.file.Paths.get('$path').getParent())
                        if (directory != null) directory.delete(null)
                    }
                }))
            }
        }), ModalityState.nonModal())
        """,
        false,
    )
}
