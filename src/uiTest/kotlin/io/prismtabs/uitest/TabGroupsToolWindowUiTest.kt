package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.utils.Keyboard
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

                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Green", name = "CollapseMe")
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

    /**
     * The context menu must act on the row the user right-clicked, whatever was selected before —
     * the regression guarded here is a menu that comes up entirely greyed out, which is what
     * happens when a right-click doesn't move the list's selection (Swing moves it for the left
     * button only) and nothing is selected. Deleting a group is the everyday way into that state:
     * the deleted row was the selected one.
     *
     * Both halves of the fix are asserted from the user's own sequence — right-click with nothing
     * selected, then delete, then right-click again straight after: `installRowSelectingPopupMenu`
     * selects the clicked row, and `TabGroupsPanel.refresh` falls the selection onto a surviving
     * row instead of clearing it, which is what keeps the *toolbar* alive after a delete too.
     */
    @Test
    fun `right-clicking a group row acts on that row, with nothing selected and straight after a delete`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "RightClickA")
                assignFileToNewGroup("Second.kt", "RightClickB")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 2 }

                // Nothing has ever been selected in this list, so this is the plain form of the bug.
                val list = remoteRobot.toolWindowGroupList()
                assertTrue(list.collectSelectedItems().isEmpty(), "precondition: the list starts with no selection")
                list.findText("RightClickA").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().all { it.endsWith("=true") },
                    "a right-click on a row must enable that row's actions, got ${remoteRobot.contextMenuItemStates()}",
                )
                assertTrue(
                    remoteRobot.toolWindowGroupList().collectSelectedItems().any { it.contains("RightClickA") },
                    "the right-clicked row must be the selected one, got ${remoteRobot.toolWindowGroupList().collectSelectedItems()}",
                )

                // Delete it from that very menu: the selected row is the one that disappears.
                remoteRobot.actionMenuItem("Delete Group").click()
                dialog("Delete Tab Group") {
                    find<JButtonFixture>(JButtonFixture.byText("Delete")).click()
                }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 1 }
                assertTrue(
                    remoteRobot.toolWindowGroupList().collectSelectedItems().any { it.contains("RightClickB") },
                    "deleting the selected group must leave the surviving row selected, not an empty selection",
                )

                // ...and the very next right-click, with no left-click to revive the panel first.
                remoteRobot.toolWindowGroupList().findText("RightClickB").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().all { it.endsWith("=true") },
                    "the menu must be live straight after a delete, got ${remoteRobot.contextMenuItemStates()}",
                )
                Keyboard(remoteRobot).escape()
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }
}
