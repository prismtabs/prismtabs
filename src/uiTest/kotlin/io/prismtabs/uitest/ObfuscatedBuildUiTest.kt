package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import java.time.Duration.ofSeconds

/**
 * The obfuscated jar Marketplace ships, driven in a real IDE: the main paths through the code the
 * obfuscator renamed — the tab popup, saving, the tool window, and the palette settings page. Runs only against `runIdeForUiTestsObfuscated` (see [RunsOnObfuscatedBuild]).
 *
 * Each test works on a file and group of its own, named per run, and removes them through the UI.
 * The resets the other classes start from call the plugin's services by name, which this build no
 * longer has.
 */
@RunsOnObfuscatedBuild
@ExtendWith(RemoteRobotExtension::class)
class ObfuscatedBuildUiTest {
    init {
        StepsLogger.init()
    }

    /** Guards every other test here against passing on a plain jar the build let through. */
    @Test
    fun `the sandbox runs the obfuscated jar`(remoteRobot: RemoteRobot) {
        assertEquals(
            mapOf("io.prismtabs.provider.TabGroupColorProvider" to true, "io.prismtabs.service.TabGroupVisibility" to false),
            remoteRobot.pluginClassesPresent("io.prismtabs.provider.TabGroupColorProvider", "io.prismtabs.service.TabGroupVisibility"),
            "plugin.xml's classes keep their names and the rest are renamed",
        )
    }

    @Test
    fun `a group made from the tab popup colours the tab, is saved in the released format, and deletes from the popup`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            withOwnFileAndGroupName(remoteRobot) { file, group ->
                createGroupFromPopup(remoteRobot, file, "Green", group)
                assertEquals(themedPreset("green"), tabRgb(file))

                forceSaveSettings()
                val saved = savedTabGroups()
                assertTrue(
                    Regex("""<GroupEntry>(?:(?!</GroupEntry>).)*<option name="name" value="$group" />""", RegexOption.DOT_MATCHES_ALL)
                        .containsMatchIn(saved),
                    "tabGroups.xml must hold the group as a <GroupEntry> with its name option:\n$saved",
                )
                assertTrue(
                    saved.contains("""<entry key="file://${'$'}PROJECT_DIR${'$'}/$file" value="""),
                    "tabGroups.xml must hold the file's assignment:\n$saved",
                )

                deleteGroupFromPopup(remoteRobot, file, group)
            }
        }
    }

    @Test
    fun `the tool window lists the group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            withOwnFileAndGroupName(remoteRobot) { file, group ->
                createGroupFromPopup(remoteRobot, file, "Purple", group)
                try {
                    showTabGroupsToolWindow()
                    waitFor(ofSeconds(10), errorMessage = "the tool window never listed $group: ${remoteRobot.toolWindowGroupRows()}") {
                        remoteRobot.toolWindowGroupRows().any { it.startsWith("$group ") }
                    }
                } finally {
                    hideTabGroupsToolWindow()
                }
                deleteGroupFromPopup(remoteRobot, file, group)
            }
        }
    }

    /**
     * Starts and ends on Restore Defaults, so a run that failed half way through cannot leave its
     * renamed preset for the next one.
     */
    @Test
    fun `a preset renamed on the palette settings page is what the tab popup offers`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            withOwnFileAndGroupName(remoteRobot) { file, renamed ->
                try {
                    restorePaletteDefaults(remoteRobot)

                    openPrismTabsSettings()
                    val settings = settingsDialog(ofSeconds(30))
                    val nameField = settings.firstPresetNameField()
                    nameField.click()
                    Keyboard(remoteRobot).hotKey(remoteRobot.menuShortcutKey(), KeyEvent.VK_A)
                    Keyboard(remoteRobot).enterText(renamed)
                    waitFor(ofSeconds(10)) { settings.firstPresetNameField().text == renamed }
                    settings.find<JButtonFixture>(JButtonFixture.byText("OK")).click()
                    waitFor(ofSeconds(10)) { !hasSettingsDialog() }

                    assertEquals(listOf(renamed, "Blue"), popupPresetRows(remoteRobot, file).take(2))

                    restorePaletteDefaults(remoteRobot)
                    assertEquals(listOf("Grey", "Blue"), popupPresetRows(remoteRobot, file).take(2))
                } finally {
                    if (hasSettingsDialog()) Keyboard(remoteRobot).escape()
                }
            }
        }
    }

    /**
     * Runs [body] with a fresh fixture file open in a tab and a group name to use, both unique to
     * this run, then closes and deletes the file.
     *
     * A test that fails before deleting its group leaves the file as that group's last member, so
     * deleting the file brings up the "Empty Tab Group" prompt; that is answered here, so the
     * prompt does not block whatever runs next.
     */
    private fun IdeaFrame.withOwnFileAndGroupName(remoteRobot: RemoteRobot, body: IdeaFrame.(file: String, group: String) -> Unit) {
        val stamp = System.currentTimeMillis()
        val file = "Obfuscated$stamp.kt"
        hideTabGroupsToolWindow()
        createFixtureFile(file)
        var groupLeftBehind = false
        try {
            openFile(file)
            body(file, "Obfuscated $stamp")
        } finally {
            groupLeftBehind = runCatching { tabRgb(file) != null }.getOrDefault(false)
            repeat(3) { Keyboard(remoteRobot).escape() }
            closeFile(file)
            deleteFixtureFile(file)
            if (groupLeftBehind) {
                dialog("Empty Tab Group", ofSeconds(20)) { find<JButtonFixture>(JButtonFixture.byText("Delete Group")).click() }
            }
        }
    }

    private fun IdeaFrame.createGroupFromPopup(remoteRobot: RemoteRobot, file: String, colour: String, group: String) {
        remoteRobot.openTabGroupPopupOn(this, file)
        remoteRobot.clickPopupItem(colour)
        dialog("New Tab Group") {
            find<JTextFieldFixture>(JTextFieldFixture.byType()).text = group
            find<JButtonFixture>(JButtonFixture.byText("OK")).click()
        }
        waitFor(ofSeconds(10), errorMessage = "$file's tab never took a colour") { tabRgb(file) != null }
    }

    private fun IdeaFrame.deleteGroupFromPopup(remoteRobot: RemoteRobot, file: String, group: String) {
        remoteRobot.openTabGroupPopupOn(this, file)
        remoteRobot.clickPopupItem("Manage Groups…")
        remoteRobot.clickPopupSubstepItem(group, "Delete Group")
        dialog("Delete Tab Group") { find<JButtonFixture>(JButtonFixture.byText("Delete")).click() }
        waitFor(ofSeconds(10), errorMessage = "$file's tab kept its colour after the group was deleted") { tabRgb(file) == null }
    }

    /** Opens the Settings page, clicks Restore Defaults, and accepts the dialog. */
    private fun IdeaFrame.restorePaletteDefaults(remoteRobot: RemoteRobot) {
        openPrismTabsSettings()
        val settings = settingsDialog(ofSeconds(30))
        settings.firstPresetNameField()
        settings.findText("Restore Defaults").click()
        waitFor(ofSeconds(10)) { settings.firstPresetNameField().text == "Grey" }
        settings.find<JButtonFixture>(JButtonFixture.byText("OK")).click()
        waitFor(ofSeconds(10)) { !hasSettingsDialog() }
    }

    /**
     * The rows the tab popup offers for [file], in order, read without choosing one. The "New Group"
     * caption is dropped: it is painted with the first row and read back after it.
     */
    private fun IdeaFrame.popupPresetRows(remoteRobot: RemoteRobot, file: String): List<String> {
        remoteRobot.openTabGroupPopupOn(this, file)
        val rows = remoteRobot.popupItemTexts().filter { it != "New Group" }
        Keyboard(remoteRobot).escape()
        return rows
    }

    /** The first preset's name field, once the PrismTabs page has rendered its fields. */
    private fun DialogFixture.firstPresetNameField(): JTextFieldFixture {
        val fields = byXpath("//div[@class='JBTextField']")
        waitFor(ofSeconds(30), errorMessage = "the PrismTabs settings page never rendered its fields") {
            findAll<JTextFieldFixture>(fields).isNotEmpty()
        }
        return findAll<JTextFieldFixture>(fields).first()
    }

    /** The fixture project's `.idea/tabGroups.xml` as it is on disk. */
    private fun IdeaFrame.savedTabGroups(): String = callJs(
        """
        importPackage(com.intellij.openapi.wm.impl)
        var project = ProjectFrameHelper.getFrameHelper(component).getProject()
        '' + java.nio.file.Files.readString(java.nio.file.Paths.get(project.getBasePath(), '.idea', 'tabGroups.xml'))
        """,
        true,
    )

    /** Whether each of [names] loads from the plugin's own class loader. */
    private fun RemoteRobot.pluginClassesPresent(vararg names: String): Map<String, Boolean> = callJs<String>(
        """
        importPackage(com.intellij.ide.plugins)
        importPackage(com.intellij.openapi.extensions)
        var loader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
        var names = '${names.joinToString(",")}'.split(',')
        var out = []
        for (var i = 0; i < names.length; i++) {
            var present = true
            try { java.lang.Class.forName(names[i], false, loader) } catch (e) { present = false }
            out.push(names[i] + '=' + present)
        }
        out.join('|')
        """,
        true,
    ).split('|').associate { it.substringBefore('=') to it.substringAfter('=').toBoolean() }
}
