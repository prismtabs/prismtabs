package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * End-to-end coverage of [io.prismtabs.action.AddToActiveGroupAction] — the active-group shortcut
 * on the Project view's context menu — against a real, running sandbox IDE (see README
 * "UI testing" for how to launch `runIdeForUiTests` first).
 *
 * Driven through the actual tree and the actual menu rather than by invoking the action with a
 * fabricated `DataContext`: what is being tested here is precisely that the platform hands this
 * action the file the user right-clicked, and a hand-built context would assert that against
 * itself. The tab-popup half of the same shortcut is [TabGroupPopupUiTest]'s business.
 */
@ExtendWith(RemoteRobotExtension::class)
class AddToActiveGroupUiTest {
    init {
        StepsLogger.init()
    }

    /**
     * The reported gap, end to end: a file that is already open does not auto-join the active group
     * — no `fileOpened` fires for it — and the Project view's own context menu is where that is put
     * right, one right-click from the double-click that didn't.
     *
     * The file is opened into the group and then unassigned rather than opened before the group is
     * marked active: same deterministic ordering, and the same reason, as the popup half of this
     * shortcut in [TabGroupPopupUiTest].
     */
    @Test
    fun `the Project view adds an already-open file to the active group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            assignFileToNewGroup("Sample.kt", "StickyGroup")
            toggleActiveGroup("StickyGroup")
            val projectViewWasOpen = projectToolWindowVisible()
            try {
                openFile("Second.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "StickyGroup" }

                clearGroupFor("Second.kt")
                assertNull(groupNameFor("Second.kt"), "the file must start out unassigned")

                setProjectToolWindowVisible(true)
                remoteRobot.projectViewTreeShowing("Second.kt").rightClickRowWithText("Second.kt", false)
                remoteRobot.actionMenuItem("""Add to Tab Group "StickyGroup"""").click()

                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "StickyGroup" }
                assertEquals("StickyGroup", groupNameFor("Second.kt"))
            } finally {
                // Both are session-scoped state the next test would otherwise inherit: an active
                // group auto-colours whatever it opens, and a tool window that wasn't open moves
                // every editor tab this suite clicks at real screen coordinates.
                toggleActiveGroup("StickyGroup")
                setProjectToolWindowVisible(projectViewWasOpen)
            }
        }
    }

    /**
     * A group name reaches the menu intact, underscore and all.
     *
     * `Presentation.setText(String)` passes `mayContainMnemonic = true` — so the obvious way to
     * write the label would render "auth_flow" as "authflow" with F underlined, mangling a name the
     * user typed and inventing a keyboard mnemonic out of it. Group names are free text (the New
     * Group prompt checks only that they are non-blank), so this is an ordinary name, not a
     * pathological one, and the assertion is on the rendered item rather than on the label function
     * because it is the rendering that was wrong.
     */
    @Test
    fun `an underscore in a group name survives into the menu item`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            assignFileToNewGroup("Sample.kt", "auth_flow")
            toggleActiveGroup("auth_flow")
            val projectViewWasOpen = projectToolWindowVisible()
            try {
                setProjectToolWindowVisible(true)
                remoteRobot.projectViewTreeShowing("Second.kt").rightClickRowWithText("Second.kt", false)

                val items = remoteRobot.contextMenuItemStates()
                assertTrue(
                    items.any { it.startsWith("""Add to Tab Group "auth_flow"=""") },
                    "expected the name rendered verbatim, got ${items.filter { it.contains("Tab Group") }}",
                )
                Keyboard(remoteRobot).escape()
            } finally {
                toggleActiveGroup("auth_flow")
                setProjectToolWindowVisible(projectViewWasOpen)
            }
        }
    }

    /**
     * With no group marked active the item is not in the menu at all, rather than sitting there
     * greyed: the shortcut has no destination, and a permanently disabled row would be dead weight
     * in a menu this long for everyone who never marks a group active.
     *
     * A group *exists* here — it is being active, not existing, that the item hangs off.
     */
    @Test
    fun `no active group means no item in the Project view menu`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            assignFileToNewGroup("Sample.kt", "IdleGroup")
            val projectViewWasOpen = projectToolWindowVisible()
            try {
                assertNull(activeGroupName(), "this case is only meaningful with no active group")

                setProjectToolWindowVisible(true)
                remoteRobot.projectViewTreeShowing("Third.kt").rightClickRowWithText("Third.kt", false)

                val items = remoteRobot.contextMenuItemStates()
                assertTrue(items.isNotEmpty(), "no context menu opened at all")
                assertFalse(
                    items.any { it.startsWith("Add to Tab Group") },
                    "expected no active-group item with nothing active, got $items",
                )
                Keyboard(remoteRobot).escape()
            } finally {
                setProjectToolWindowVisible(projectViewWasOpen)
            }
        }
    }
}
