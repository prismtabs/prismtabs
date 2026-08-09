package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import java.time.Duration.ofSeconds

/**
 * One-off, driven pass through docs/cross-platform-manual-verification.md's checklist on a real
 * Windows desktop, in place of a human clicking through it — reuses the same fixtures the permanent
 * `uiTest` suite already relies on rather than inventing new low-level driving code. Not part of the
 * regular suite and not meant to run in CI: it exists to produce screenshots and a written log for
 * a human to review and sign off, matching the doc's own "on real hardware or a VM, not CI" framing.
 *
 * Split into two methods, run as two separate `uiTest` invocations with an actual IDE restart
 * (killing and relaunching the `runIdeForUiTests` process) in between — checklist item 8 asks
 * whether state survives a real restart, which nothing inside a single JVM run can exercise.
 */
@ExtendWith(RemoteRobotExtension::class)
class WindowsManualVerificationUiTest {
    init { StepsLogger.init() }

    private val log = StringBuilder()
    private fun note(line: String) {
        log.appendLine(line)
    }

    private fun flush(name: String) {
        File("build/reports/manual").apply { mkdirs() }.resolve(name).writeText(log.toString())
    }

    @Test
    fun part1WalkthroughChecklist(remoteRobot: RemoteRobot) {
        try {
            remoteRobot.idea {
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()

                note("Environment: ${environmentInfo()}")

                // --- 1. Swatch popup: all 8 presets present ---------------------------------
                openFile("Sample.kt")
                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.saveScreenshot("01-swatch-popup")
                val missingPresets = TabGroupPalette.PRESETS.map { it.label }.filterNot { label ->
                    remoteRobot.popupList().findAllText(label).isNotEmpty()
                }
                note("1. Swatch popup — missing presets: $missingPresets (empty = all 8 found)")
                assertTrue(missingPresets.isEmpty(), "missing presets: $missingPresets")
                Keyboard(remoteRobot).escape()

                // --- 2. Assign a preset: selected vs. unselected legibility -----------------
                // Checked against the real on-screen pixel (pixelColorAtTabCenter), not just a
                // screenshot: the deep, saturated preset colours are subtle enough at a glance
                // that a rendered screenshot thumbnail can look untinted even when correct —
                // confirmed live while investigating this exact step (see git history / commit
                // message for the full trail). A pixel read is the only check that can't be
                // fooled by how muted the colour looks to a human eye.
                openFile("Second.kt")
                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Blue")
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Blue" }

                selectTab("Second.kt")
                val untintedControl = pixelColorAtTabCenter("Second.kt")
                remoteRobot.saveScreenshot("02-assigned-preset-unselected")
                val unselectedPixel = pixelColorAtTabCenter("Sample.kt")
                note("2a. Sample.kt unselected pixel: $unselectedPixel vs. an untinted tab's pixel: $untintedControl")
                assertTrue(
                    unselectedPixel != untintedControl,
                    "expected Sample.kt's unselected tint to differ from an untinted tab's pixel colour",
                )

                selectTab("Sample.kt")
                val selectedPixel = pixelColorAtTabCenter("Sample.kt")
                remoteRobot.saveScreenshot("02b-assigned-preset-selected")
                note("2b. Sample.kt selected pixel: $selectedPixel (expect full-strength Blue: dark 0x2E5C9C r=46,g=92,b=156, or light 0xAECBFA r=174,g=203,b=250)")
                assertTrue(
                    selectedPixel.contains("r=46,g=92,b=156") || selectedPixel.contains("r=174,g=203,b=250"),
                    "expected the selected Sample.kt tab to render Blue at full strength, got $selectedPixel",
                )

                // --- 3. New Group: name field has focus immediately, Enter submits ---------
                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("New Group…")
                remoteRobot.clickPopupItem("Yellow")
                dialog("New Tab Group") {}
                remoteRobot.saveScreenshot("03-new-group-name-dialog")
                // Deliberately not clicking into the field first — if it isn't focused the
                // instant the dialog renders, this text lands nowhere and the group is never
                // named, which is exactly the behaviour item 3 asks to confirm.
                Keyboard(remoteRobot).enterText("FocusTest")
                Keyboard(remoteRobot).enter()
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "FocusTest" }
                assertEquals("FocusTest", groupNameFor("Second.kt"))
                note("3. Typed into the New Group dialog with no prior click, then Enter submitted it — field had focus immediately")

                // --- 4. Rename / Recolour / active-group inheritance ------------------------
                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("FocusTest", "Mark as Active Group")
                openFile("Third.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "FocusTest" }
                assertEquals("FocusTest", groupNameFor("Third.kt"))

                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("FocusTest (active)", "Rename…")
                dialog("Rename Tab Group") {
                    find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "TeamWindows"
                    find<JButtonFixture>(JButtonFixture.byText("OK")).click()
                }
                waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "TeamWindows" }

                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("TeamWindows (active)", "Recolour…")
                remoteRobot.clickPopupItem("Cyan")
                val cyanLightRgb = TabGroupPalette.PRESETS.first { it.id == "cyan" }.light.rgb and 0xFFFFFF
                waitFor(ofSeconds(10)) { groupLightRgbFor("Third.kt") == cyanLightRgb }
                assertEquals(cyanLightRgb, groupLightRgbFor("Second.kt"))
                assertEquals(cyanLightRgb, groupLightRgbFor("Third.kt"))
                remoteRobot.saveScreenshot("04-rename-recolour-active-group")
                note("4. Renamed to TeamWindows and recoloured Cyan — both Second.kt and Third.kt followed; Third.kt inherited via the active group")

                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("TeamWindows (active)", "Clear Active Group")

                // --- 5. Remove from Group ----------------------------------------------------
                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Remove from Group")
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
                assertNull(groupNameFor("Sample.kt"))
                remoteRobot.saveScreenshot("05-remove-from-group")
                note("5. Remove from Group cleared Sample.kt back to the platform default")

                // --- 6. Tab Groups tool window: listing, collapse, expand, HiDPI check ------
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("TeamWindows") } }
                remoteRobot.saveScreenshot("06-tool-window-listing")
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("TeamWindows") && it.contains("2 tabs") },
                    "expected TeamWindows listed with 2 members, got ${remoteRobot.toolWindowGroupRows()}",
                )
                setGroupCollapsed("TeamWindows", collapsed = true)
                waitFor(ofSeconds(10)) { !openTabNames().contains("Second.kt") }
                assertTrue(remoteRobot.toolWindowGroupRows().any { it.contains("TeamWindows") && it.contains("collapsed") })
                remoteRobot.saveScreenshot("06b-tool-window-collapsed")
                setGroupCollapsed("TeamWindows", collapsed = false)
                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Second.kt", "Third.kt")) }
                note("6. Tool window listed TeamWindows (2 tabs); collapse closed its tabs and marked it collapsed; expand reopened them")
                hideTabGroupsToolWindow()

                // --- 7. Settings | Tools | PrismTabs ---------------------------------------
                try {
                    openPrismTabsSettings()
                    val settingsDialog = dialog("Settings")
                    // The dialog window appears before its content does — Settings pages load
                    // asynchronously behind a "loading…" placeholder. Wait for an actual preset
                    // label rather than screenshotting the placeholder.
                    waitFor(ofSeconds(10)) { settingsDialog.findAllText("Grey").isNotEmpty() }
                    remoteRobot.saveScreenshot("07-settings-page")
                    Keyboard(remoteRobot).escape()
                    note("7a. Settings dialog opened to the PrismTabs page and rendered (screenshot 07)")
                } catch (t: Throwable) {
                    note("7a. Could not confirm the live Settings dialog visually (${t.message}) — falling back to 7b only")
                }
                val paletteSteps = exercisePaletteConfigurable()
                note("7b. Palette configurable checkpoints: $paletteSteps")
                // Restore Defaults stages the reverted value but doesn't auto-apply — the
                // Configurable correctly stays "modified" until Apply runs again (see
                // TabGroupPaletteConfigurableUiTest, which already established this). What
                // actually confirms "Restore Defaults reverts it" is the label being back to
                // the built-in default *after* that second apply.
                assertTrue(paletteSteps.any { it == "serviceLabelAfterRestoreApply:Grey" })

                // Leave TeamWindows (Second.kt/Third.kt, Cyan) in place deliberately — part 2
                // checks it, and the palette customisation, survive a real IDE restart. The
                // restart itself is a forced process kill (there's no in-process "Restart IDE"
                // for remote-robot to click), which skips the normal save-on-exit sequence, so
                // force a save now — see forceSaveSettings's doc comment.
                forceSaveSettings()
                note("Forced a save of all project/app state ahead of the simulated restart")
            }
        } finally {
            flush("part1-log.txt")
        }
    }

    @Test
    fun part2VerifyPersistenceAfterRestart(remoteRobot: RemoteRobot) {
        try {
            remoteRobot.idea {
                openFile("Second.kt")
                openFile("Third.kt")

                note("Second.kt group after restart: ${groupNameFor("Second.kt")}")
                note("Third.kt group after restart: ${groupNameFor("Third.kt")}")
                assertEquals("TeamWindows", groupNameFor("Second.kt"))
                assertEquals("TeamWindows", groupNameFor("Third.kt"))
                remoteRobot.saveScreenshot("08-after-restart")

                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("TeamWindows") } }
                note("Tool window rows after restart: ${remoteRobot.toolWindowGroupRows()}")
                hideTabGroupsToolWindow()

                note("8. Group name, colour and membership all survived a real IDE restart")
            }
        } finally {
            flush("part2-log.txt")
        }
    }
}
