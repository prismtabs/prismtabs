package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import java.time.Duration.ofSeconds

/**
 * End-to-end coverage of [io.prismtabs.ui.TabGroupPaletteConfigurable] against a real, running
 * sandbox IDE — see engineering notes "UI testing" for how to launch `runIdeForUiTests` first, and
 * [IdeaFrame.exercisePaletteConfigurable] for why this drives the Configurable through direct
 * Swing/reflection calls rather than mouse-clicking the Settings tree and a native colour chooser.
 */
@ExtendWith(RemoteRobotExtension::class)
class TabGroupPaletteConfigurableUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `editing a preset's name applies to the palette service, and Restore Defaults reverts it`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroupPalette()

                val checkpoints = exercisePaletteConfigurable().associate {
                    val (label, value) = it.split(":", limit = 2)
                    label to value
                }

                assertEquals("Grey", checkpoints["initialLabel"], "expected the first row to start out as the built-in \"Grey\" preset")
                assertEquals("true", checkpoints["modifiedBeforeApply"], "editing the name field should make the Configurable modified")
                assertEquals("false", checkpoints["modifiedAfterApply"], "apply() should clear the modified flag")
                assertEquals("MyGrey", checkpoints["serviceLabelAfterApply"], "apply() should persist the edited name to TabGroupPaletteService")
                assertEquals("Grey", checkpoints["labelAfterRestoreDefaults"], "the Restore Defaults link should push the factory default back into the field")
                assertEquals("true", checkpoints["modifiedAfterRestoreDefaults"], "Restore Defaults should be seen as a pending change until applied")
                assertEquals("Grey", checkpoints["serviceLabelAfterRestoreApply"], "applying after Restore Defaults should drop the override")
            } finally {
                resetTabGroupPalette()
            }
        }
    }

    @Test
    fun `the page has a name field and a light and a dark colour picker for each of the eight presets`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroupPalette()
            val layout = paletteConfigurableLayout()

            assertEquals(TabGroupPalette.PRESETS.joinToString(",") { "${it.label}:" }, layout["labels"], "one row per preset")
            assertEquals(TabGroupPalette.PRESETS.joinToString(",") { it.label }, layout["names"], "each row's name field holds its preset's name")
            assertEquals("16", layout["pickers"], "a light and a dark colour picker per row")
            assertEquals(
                TabGroupPalette.PRESETS.joinToString(",") { "${it.light.rgb and 0xFFFFFF},${it.dark.rgb and 0xFFFFFF}" },
                layout["colours"],
                "each picker starts on its preset's colour",
            )
        }
    }

    @Test
    fun `Restore Defaults reverts a preset's name and both its colours`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroupPalette()
                val grey = TabGroupPalette.PRESETS.first()
                val defaults = "${grey.label}|${grey.light.rgb and 0xFFFFFF}|${grey.dark.rgb and 0xFFFFFF}"

                val checkpoints = exercisePaletteRestoreDefaultsWithColours()

                assertEquals("Custom|${0x123456}|${0x654321}", checkpoints["afterEdit"], "the edit must apply first")
                assertEquals(defaults, checkpoints["pageAfterRestore"], "Restore Defaults must put the factory name and colours back on the page")
                assertEquals(defaults, checkpoints["afterRestore"], "and applying that must reach the service")
            } finally {
                resetTabGroupPalette()
            }
        }
    }

    /**
     * The whole round trip through the real Settings dialog: type a new name, set new colours, press
     * Apply and OK. The swatch row then offers the edited preset — name and dot — and a group started
     * from it takes the new colours; a group made from that preset *before* the edit keeps its own,
     * as the page itself tells the user.
     */
    @Test
    fun `a preset edited in Settings is what the swatch row offers next, while existing groups keep their colour`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                openFile("Sample.kt")
                openFile("Second.kt")
                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "BeforeEdit")

                openPrismTabsSettings()
                val settings = settingsDialog(ofSeconds(30))
                settings.editFirstPresetUntilPending(remoteRobot, "Work", lightRgb = 0x123456, darkRgb = 0x654321)
                settings.find<JButtonFixture>(JButtonFixture.byText("Apply")).click()
                waitFor(ofSeconds(10), errorMessage = "Apply did not save the edit: ${palettePreset(0)}") {
                    palettePreset(0) == "Work|${0x123456}|${0x654321}"
                }
                settings.find<JButtonFixture>(JButtonFixture.byText("OK")).click()
                waitFor(ofSeconds(10)) { !hasSettingsDialog() }

                val edited = themed(0x123456, 0x654321)
                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                assertEquals("Work" to edited, remoteRobot.popupRowSwatches().first(), "the swatch row must offer the edited preset")
                remoteRobot.clickPopupItem("Work")
                dialog("New Tab Group") {
                    assertEquals("Work", find<JTextFieldFixture>(JTextFieldFixture.byType()).text)
                    find<JButtonFixture>(JButtonFixture.byText("OK")).click()
                }
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Work" }
                assertEquals(0x123456, groupLightRgbFor("Second.kt"))
                assertEquals(0x654321, groupDarkRgbFor("Second.kt"))
                waitFor(ofSeconds(10)) { tabRgb("Second.kt") == edited }

                val grey = TabGroupPalette.PRESETS.first()
                assertEquals(grey.light.rgb and 0xFFFFFF, groupLightRgbFor("Sample.kt"), "a group made before the edit keeps its colour")
                assertEquals(themedPreset("grey"), tabRgb("Sample.kt"), "and so does its tab")
            } finally {
                if (hasSettingsDialog()) Keyboard(remoteRobot).escape()
                resetTabGroupPalette()
            }
        }
    }

    @Test
    fun `cancelling the Settings dialog discards an edit`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroupPalette()
                openPrismTabsSettings()
                val settings = settingsDialog(ofSeconds(30))
                settings.editFirstPresetUntilPending(remoteRobot, "Discarded", lightRgb = 0x0A0B0C, darkRgb = 0x0C0B0A)

                settings.find<JButtonFixture>(JButtonFixture.byText("Cancel")).click()
                waitFor(ofSeconds(10)) { !hasSettingsDialog() }

                val grey = TabGroupPalette.PRESETS.first()
                assertEquals("Grey|${grey.light.rgb and 0xFFFFFF}|${grey.dark.rgb and 0xFFFFFF}", palettePreset(0))
                assertEquals("Grey", paletteConfigurableLayout()["names"]?.substringBefore(','), "a page opened afterwards must show the old name")
            } finally {
                if (hasSettingsDialog()) Keyboard(remoteRobot).escape()
                resetTabGroupPalette()
            }
        }
    }
}

/**
 * Types [name] over the first preset's name field on the open Settings dialog's palette page and sets
 * its two colours, then waits until the edit is actually **pending** — the field holds [name] and
 * Apply is enabled — typing again if not.
 *
 * The retry is for a measured race, not caution: the Settings dialog runs the page's own `reset()`
 * once it has shown it, and an edit typed before that is quietly put back to the saved values. Left
 * unguarded, Apply then saves nothing, and — worse — Cancel "discards" an edit that was never there,
 * so a test of it would pass on nothing.
 */
private fun DialogFixture.editFirstPresetUntilPending(remoteRobot: RemoteRobot, name: String, lightRgb: Int, darkRgb: Int) {
    val fields = byXpath("//div[@class='JBTextField']")
    waitFor(ofSeconds(30), errorMessage = "the PrismTabs page never rendered") { findAll<JTextFieldFixture>(fields).any { it.text == "Grey" } }
    val apply = find<JButtonFixture>(JButtonFixture.byText("Apply"))
    waitFor(ofSeconds(20), errorMessage = "the edit never became pending on the page") {
        // The first text field on the page is the first preset's name; each colour picker's own hex
        // field comes after its row's name.
        val nameField = findAll<JTextFieldFixture>(fields).first()
        if (nameField.text != name) {
            nameField.click()
            Keyboard(remoteRobot).hotKey(remoteRobot.menuShortcutKey(), KeyEvent.VK_A)
            Keyboard(remoteRobot).enterText(name)
        }
        remoteRobot.setPresetColoursInOpenSettings(0, lightRgb, darkRgb)
        findAll<JTextFieldFixture>(fields).first().text == name && apply.callJs<Boolean>("component.isEnabled()", true)
    }
}
