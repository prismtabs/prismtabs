package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.search.locators.byXpath
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
            openFile("Sample.kt")
            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Blue").click()

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Blue" }
            assertEquals("Blue", groupNameFor("Sample.kt"))
        }
    }

    @Test
    fun `New Group prompts for a name and assigns the named group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            openFile("Sample.kt")
            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("New Group…").click()
            remoteRobot.popupList().findText("Purple").click()

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
            openFile("Sample.kt")
            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Grey").click()
            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Grey" }

            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Remove from Group").click()

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
            openFile("Sample.kt")
            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("New Group…").click()
            remoteRobot.popupList().findText("Blue").click()
            dialog("New Tab Group") {
                find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "StickyGroup"
                find<JButtonFixture>(JButtonFixture.byText("OK")).click()
            }
            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "StickyGroup" }

            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Manage Groups…").click()
            remoteRobot.popupList().findText("StickyGroup").click()
            remoteRobot.popupList().findText("Mark as Active Group").click()

            openFile("Second.kt")
            openFile("Third.kt")
            waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "StickyGroup" }
            waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "StickyGroup" }
            assertEquals("StickyGroup", groupNameFor("Second.kt"))
            assertEquals("StickyGroup", groupNameFor("Third.kt"))

            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Manage Groups…").click()
            remoteRobot.popupList().findText("StickyGroup (active)").click()
            remoteRobot.popupList().findText("Rename…").click()
            dialog("Rename Tab Group") {
                find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "RenamedGroup"
                find<JButtonFixture>(JButtonFixture.byText("OK")).click()
            }
            waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "RenamedGroup" }
            assertEquals("RenamedGroup", groupNameFor("Sample.kt"))
            assertEquals("RenamedGroup", groupNameFor("Third.kt"))

            val purple = TabGroupPalette.PRESETS.first { it.id == "purple" }
            val purpleLightRgb = purple.light.rgb and 0xFFFFFF

            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Manage Groups…").click()
            remoteRobot.popupList().findText("RenamedGroup (active)").click()
            remoteRobot.popupList().findText("Recolour…").click()
            remoteRobot.popupList().findText("Purple").click()

            waitFor(ofSeconds(10)) { groupLightRgbFor("Sample.kt") == purpleLightRgb }
            assertEquals(purpleLightRgb, groupLightRgbFor("Sample.kt"))
            assertEquals(purpleLightRgb, groupLightRgbFor("Second.kt"))
            assertEquals(purpleLightRgb, groupLightRgbFor("Third.kt"))

            // Deactivate: an active group is session-scoped state that would otherwise leak into
            // whichever test happens to run next and open a fresh file (see TabGroupService's
            // doc comment on activeGroupId).
            openTabGroupPopup(remoteRobot, this, "Sample.kt")
            remoteRobot.popupList().findText("Manage Groups…").click()
            remoteRobot.popupList().findText("RenamedGroup (active)").click()
            remoteRobot.popupList().findText("Clear Active Group").click()
        }
    }

    // A plain function, not an `IdeaFrame` extension: the popup/menu it opens renders in a
    // separate top-level window outside `IdeFrameImpl`'s component subtree, so finding it needs
    // an unscoped `remoteRobot.find`, not `IdeaFrame`'s subtree-scoped one (see Popup.kt).
    private fun openTabGroupPopup(remoteRobot: RemoteRobot, ideaFrame: IdeaFrame, path: String) {
        // The concrete runtime class is EditorTabLabel (fileEditor's TabLabel subclass), not
        // TabLabel itself — confirmed against the live component hierarchy, not javap alone.
        // Filtered by @accessiblename (the tab's file name, confirmed live via
        // `curl http://127.0.0.1:8082` — there is no `@text` on this component) since Phase 3
        // tests can have more than one tab open at once, unlike Phase 2's single-tab fixture.
        val tab = ideaFrame.find<ComponentFixture>(byXpath("//div[@class='EditorTabLabel' and @accessiblename='$path']"), ofSeconds(20))
        tab.rightClick()
        remoteRobot.actionMenuItem("Tab Group…").click()
        remoteRobot.popupList()
    }
}
