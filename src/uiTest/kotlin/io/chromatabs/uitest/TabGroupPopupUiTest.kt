package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
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
            openTabGroupPopup(remoteRobot, this)
            remoteRobot.popupList().findText("Blue").click()

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Blue" }
            assertEquals("Blue", groupNameFor("Sample.kt"))
        }
    }

    @Test
    fun `New Group prompts for a name and assigns the named group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            openFile("Sample.kt")
            openTabGroupPopup(remoteRobot, this)
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
            openTabGroupPopup(remoteRobot, this)
            remoteRobot.popupList().findText("Grey").click()
            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Grey" }

            openTabGroupPopup(remoteRobot, this)
            remoteRobot.popupList().findText("Remove from Group").click()

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
            assertNull(groupNameFor("Sample.kt"))
        }
    }

    // A plain function, not an `IdeaFrame` extension: the popup/menu it opens renders in a
    // separate top-level window outside `IdeFrameImpl`'s component subtree, so finding it needs
    // an unscoped `remoteRobot.find`, not `IdeaFrame`'s subtree-scoped one (see Popup.kt).
    private fun openTabGroupPopup(remoteRobot: RemoteRobot, ideaFrame: IdeaFrame) {
        // The concrete runtime class is EditorTabLabel (fileEditor's TabLabel subclass), not
        // TabLabel itself — confirmed against the live component hierarchy, not javap alone.
        val tab = ideaFrame.find<ComponentFixture>(byXpath("//div[@class='EditorTabLabel']"), ofSeconds(20))
        tab.rightClick()
        remoteRobot.actionMenuItem("Tab Group…").click()
        remoteRobot.popupList()
    }
}
