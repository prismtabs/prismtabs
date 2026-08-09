package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * Phase 4, end-to-end against a real sandbox IDE — see README "UI testing" for how to launch
 * `runIdeForUiTests` first. Covers the plan's stated acceptance criterion verbatim: collapse a
 * group, see its tabs leave the strip while the group stays listed in the tool window, then expand
 * and see the tabs return in order.
 */
@ExtendWith(RemoteRobotExtension::class)
class TabGroupsToolWindowUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `collapsing a group closes its tabs and keeps it listed, expanding restores them in order`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")

                assignNewGroup(remoteRobot, this, "Sample.kt", colour = "Green", name = "CollapseMe")
                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Add to Group…")
                remoteRobot.clickPopupItem("CollapseMe")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "CollapseMe" }

                openFile("Third.kt")
                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt", "Third.kt")) }

                // The tool window lists the group with its member count before anything collapses.
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("CollapseMe") } }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("CollapseMe") && it.contains("2 tabs") },
                    "expected the tool window to list CollapseMe with 2 members, got ${remoteRobot.toolWindowGroupRows()}",
                )

                setGroupCollapsed("CollapseMe", collapsed = true)

                // Collapse closes exactly the group's members; the unrelated tab stays put.
                waitFor(ofSeconds(10)) { !openTabNames().contains("Sample.kt") && !openTabNames().contains("Second.kt") }
                assertEquals(listOf("Third.kt"), openTabNames())

                // ...and the group is still listed, now marked collapsed — the whole point of 4b:
                // a collapsed group must remain reachable somewhere the tab strip cannot show it.
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("CollapseMe") && it.contains("collapsed") },
                    "expected CollapseMe to still be listed and marked collapsed, got ${remoteRobot.toolWindowGroupRows()}",
                )

                setGroupCollapsed("CollapseMe", collapsed = false)

                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt")) }
                val reopened = openTabNames().filter { it == "Sample.kt" || it == "Second.kt" }
                assertEquals(listOf("Sample.kt", "Second.kt"), reopened, "expected members to reopen in their recorded order")
                assertTrue(
                    remoteRobot.toolWindowGroupRows().none { it.contains("CollapseMe") && it.contains("collapsed") },
                    "expected CollapseMe to no longer read as collapsed after expanding",
                )
            } finally {
                // In a finally, not at the end of the happy path: a failure here would otherwise
                // leave the tool window open and break the *next* test's tab right-click. See
                // IdeaFrame.hideTabGroupsToolWindow.
                hideTabGroupsToolWindow()
            }
        }
    }

    private fun assignNewGroup(remoteRobot: RemoteRobot, ideaFrame: IdeaFrame, path: String, colour: String, name: String) {
        remoteRobot.openTabGroupPopupOn(ideaFrame, path)
        remoteRobot.clickPopupItem("New Group…")
        remoteRobot.clickPopupItem(colour)
        ideaFrame.dialog("New Tab Group") {
            find<JTextFieldFixture>(JTextFieldFixture.byType()).text = name
            find<JButtonFixture>(JButtonFixture.byText("OK")).click()
        }
        waitFor(ofSeconds(10)) { ideaFrame.groupNameFor(path) == name }
    }
}
