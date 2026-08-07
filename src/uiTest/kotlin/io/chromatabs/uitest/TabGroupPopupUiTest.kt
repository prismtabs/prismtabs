package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.utils.waitFor
import io.chromatabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * End-to-end coverage of [io.chromatabs.ui.TabGroupPopup] against a real, running sandbox IDE —
 * see README "UI testing" for how to launch `runIdeForUiTests` first. Assertions go through
 * [IdeaFrame.groupNameFor] (a `TabGroupService.groupFor` call over the wire) rather than reading
 * Swing pixel colours: it's what this popup actually owns, and it sidesteps `TabLabel`'s
 * paint-timing and protected-member-access issues entirely.
 */
@ExtendWith(RemoteRobotExtension::class)
class TabGroupPopupUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `clicking a preset dot assigns the shared colour group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Blue")

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Blue" }
            assertEquals("Blue", groupNameFor("Sample.kt"))
        }
    }

    @Test
    fun `New Group prompts for a name and assigns the named group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("New Group…")
            remoteRobot.clickPopupItem("Purple")

            dialog("New Tab Group") {
                find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "Auth"
                find<JButtonFixture>(JButtonFixture.byText("OK")).click()
            }

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Auth" }
            assertEquals("Auth", groupNameFor("Sample.kt"))
        }
    }

    @Test
    fun `Remove from Group clears an assignment made via the popup`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Grey")
            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Grey" }

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Remove from Group")

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
            assertNull(groupNameFor("Sample.kt"))
        }
    }

    /**
     * Phase 3, end-to-end, matching the plan's stated acceptance criterion verbatim: create a
     * group, mark it active, open three files and see them all inherit its colour, rename the
     * group, recolour it, and see every member follow both changes.
     */
    @Test
    fun `an active group colours newly opened files, and rename recolour follow every member`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("New Group…")
            remoteRobot.clickPopupItem("Blue")
            dialog("New Tab Group") {
                find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "StickyGroup"
                find<JButtonFixture>(JButtonFixture.byText("OK")).click()
            }
            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "StickyGroup" }

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("StickyGroup", "Mark as Active Group")

            openFile("Second.kt")
            openFile("Third.kt")
            waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "StickyGroup" }
            waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "StickyGroup" }
            assertEquals("StickyGroup", groupNameFor("Second.kt"))
            assertEquals("StickyGroup", groupNameFor("Third.kt"))

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("StickyGroup (active)", "Rename…")
            dialog("Rename Tab Group") {
                find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "RenamedGroup"
                find<JButtonFixture>(JButtonFixture.byText("OK")).click()
            }
            waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "RenamedGroup" }
            assertEquals("RenamedGroup", groupNameFor("Sample.kt"))
            assertEquals("RenamedGroup", groupNameFor("Third.kt"))

            val purple = TabGroupPalette.PRESETS.first { it.id == "purple" }
            val purpleLightRgb = purple.light.rgb and 0xFFFFFF

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("RenamedGroup (active)", "Recolour…")
            remoteRobot.clickPopupItem("Purple")

            waitFor(ofSeconds(10)) { groupLightRgbFor("Sample.kt") == purpleLightRgb }
            assertEquals(purpleLightRgb, groupLightRgbFor("Sample.kt"))
            assertEquals(purpleLightRgb, groupLightRgbFor("Second.kt"))
            assertEquals(purpleLightRgb, groupLightRgbFor("Third.kt"))

            // Deactivate: an active group is session-scoped state that would otherwise leak into
            // whichever test happens to run next and open a fresh file (see TabGroupService's
            // doc comment on activeGroupId).
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("RenamedGroup (active)", "Clear Active Group")
        }
    }
}
