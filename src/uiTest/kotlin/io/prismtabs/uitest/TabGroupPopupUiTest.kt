package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * End-to-end coverage of [io.prismtabs.ui.TabGroupPopup] against a real, running sandbox IDE —
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

    /**
     * The "New Group" caption must read at the same weight as the list's own items, not in the
     * platform's muted `separatorTextColor`.
     *
     * Asserted against `UIUtil.getListForeground()` rather than a hardcoded RGB triple, because the
     * point is *sameness*, not a particular colour: both sides move together across themes, so a
     * literal would only pin this test to the sandbox's dark theme. Read off the live
     * `GroupHeaderSeparator` rather than off screenshot pixels — the caption is drawn with
     * `UISettings.setupAntialiasing`, and LCD subpixel AA fringes glyph edges enough that sampled
     * pixels are not a trustworthy read of the colour actually set (measured: a screenshot-based
     * check on this popup reported the *same* value before and after the fix, while the component
     * genuinely went from `111,115,122` to `223,225,229`).
     */
    @Test
    fun `the New Group caption is painted at the list's own foreground`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")

            val (caption, listForeground) = remoteRobot.popupSeparatorAndListForeground()
            assertEquals(listForeground, caption, "expected the caption to match the list foreground")
        }
    }

    /**
     * The "New Group" heading gets extra room beneath it, and the plain divider above
     * "Add to Group…" does not.
     *
     * Asserted as one test because the two are a single risk: the renderer reuses **one** separator
     * component across every row that has one, so widening it in the obvious place also pushes
     * "Add to Group…" off its divider. A test that only checked the heading would pass on exactly
     * the implementation this is guarding against.
     */
    @Test
    fun `only the captioned separator gets extra room beneath it`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")

            val insets = remoteRobot.popupSeparatorInsetsByCaption()
            val heading = insets.filter { (caption, _) -> caption == "New Group" }.map { it.second }
            val plain = insets.filter { (caption, _) -> caption.isBlank() }.map { it.second }

            assertTrue(heading.isNotEmpty(), "no \"New Group\" caption in the popup at all, got $insets")
            assertTrue(plain.isNotEmpty(), "no uncaptioned rows to compare against, got $insets")
            assertTrue(
                heading.min() > plain.max(),
                "expected the \"New Group\" heading to sit on a larger bottom inset than every " +
                    "uncaptioned row, got $insets",
            )
        }
    }

    /**
     * The popup names the group the right-clicked file is already in — the only place it says so,
     * since "Remove from Group" announces that the file is assigned without naming what to.
     *
     * Read off the separator captions rather than the popup's rows, because that is what the label
     * is: asserting on item text would pass just as happily if the name turned up as an *item*,
     * which is exactly what "Add to Group…"'s substep lists one level down.
     */
    @Test
    fun `the popup captions which group the file is already in`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Auth")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")

            val captions = remoteRobot.popupSeparatorInsetsByCaption().map { it.first }
            assertTrue(
                captions.contains("In group: Auth"),
                "expected the popup to caption the file's own group, got $captions",
            )
        }
    }

    /**
     * Phase 6b's own acceptance criterion: a group's members are *files*, so the list has to name
     * every one of them whether or not it currently has a tab — that is the whole gap, since a free
     * user (no tool window) could otherwise colour a file, close it, and have no route back.
     *
     * "Second.kt" and "Third.kt" are assigned without ever being opened, which is the state a
     * restart leaves behind and the one no other surface in the plugin could answer for. The
     * rendered rows are matched by `contains`, not equality: each row is a name plus its greyed
     * directory, and pinning the exact spacing here would make the renderer's layout this test's
     * subject rather than its membership.
     */
    @Test
    fun `the file list names every member, tab open or not, and opening one brings it back`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            hideTabGroupsToolWindow()
            closeAllEditors()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Auth")
            addFileToGroup("Second.kt", "Auth")
            addFileToGroup("Third.kt", "Auth")
            assertEquals(listOf("Sample.kt"), openTabNames(), "only one member may have a tab open")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Auth", "Files…")

            val rows = remoteRobot.chooserPopupRows()
            assertEquals(3, rows.size, "expected all three members listed, got $rows")
            listOf("Sample.kt", "Second.kt", "Third.kt").forEach { name ->
                assertTrue(rows.any { it.contains(name) }, "expected $name in the file list, got $rows")
            }

            remoteRobot.clickChooserPopupItem("Second.kt")
            waitFor(ofSeconds(10)) { openTabNames().contains("Second.kt") }
            assertTrue(openTabNames().contains("Second.kt"), "choosing a row must open that file")
        }
    }

    /**
     * The stale assignment this list is the first surface to expose: a file deleted while the IDE
     * was closed fires no VFS event, so nothing ever clears its assignment and the group keeps a
     * member that isn't there. It is shown as *missing* rather than quietly skipped — a list that
     * hid it would leave a group looking fuller than it is — and choosing it offers the fix.
     *
     * The list is re-opened afterwards rather than asserting through a service call: what the user
     * came here to check is that the row is gone, and that is the same round trip they would make.
     */
    @Test
    fun `a member whose file is gone is listed as missing, and can be unassigned from there`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            hideTabGroupsToolWindow()
            closeAllEditors()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Red", name = "Stale")
            assignMissingFileToGroup("Deleted.kt", "Stale")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Stale", "Files…")

            val rows = remoteRobot.chooserPopupRows()
            assertTrue(
                rows.any { it.contains("Deleted.kt") && it.contains("missing") },
                "expected the vanished member to be listed and marked missing, got $rows",
            )

            remoteRobot.clickChooserPopupItem("Deleted.kt")
            dialog("Missing File") {
                find<JButtonFixture>(JButtonFixture.byText("Remove from Group")).click()
            }

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Stale", "Files…")
            val remaining = remoteRobot.chooserPopupRows()
            assertEquals(1, remaining.size, "expected only the real member left, got $remaining")
            assertTrue(remaining.single().contains("Sample.kt"), "expected Sample.kt to remain, got $remaining")
            // The chooser is still open — every other test here starts with a tab right-click, and
            // an open popup swallows it (see RemoteRobotExtension's own pre-test escapes).
            Keyboard(remoteRobot).escape()
        }
    }

    /**
     * An empty group has no file list to offer, so the entry is not there at all — the same rule
     * `RootStep` applies to "Manage Groups…" before any group exists. Reachable in one step: a
     * group whose last member is removed is *offered* for deletion, and the offer can be declined.
     */
    @Test
    fun `an emptied group offers no file list`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            hideTabGroupsToolWindow()
            closeAllEditors()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Green", name = "Emptied")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Remove from Group")
            dialog("Empty Tab Group") {
                find<JButtonFixture>(JButtonFixture.byText("Keep")).click()
            }
            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            // Read, don't choose: every row in this substep does something, "Rename…" included.
            val items = remoteRobot.popupSubstepItems("Emptied", anchor = "Delete Group")
            assertTrue(
                items.any { it.contains("Rename") },
                "expected the group's own action list, got $items",
            )
            assertFalse(
                items.any { it.contains("Files") },
                "an empty group must not offer a file list, got $items",
            )
            Keyboard(remoteRobot).escape()
        }
    }

    @Test
    fun `clicking a preset dot prompts for a name, pre-filled with the colour's label`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Blue")

            dialog("New Tab Group") {
                assertEquals("Blue", find<JTextFieldFixture>(JTextFieldFixture.byType()).text)
                find<JButtonFixture>(JButtonFixture.byText("OK")).click()
            }

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Blue" }
            assertEquals("Blue", groupNameFor("Sample.kt"))
        }
    }

    @Test
    fun `the name prompt assigns the typed name, not the colour's`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Purple", name = "Auth")

            assertEquals("Auth", groupNameFor("Sample.kt"))
        }
    }

    /** Cancelling the prompt must abort the whole flow — no group, and no assignment either. */
    @Test
    fun `cancelling the name prompt creates nothing`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Red")

            dialog("New Tab Group") {
                find<JButtonFixture>(JButtonFixture.byText("Cancel")).click()
            }

            waitFor(ofSeconds(10)) { groupCount() == 0 }
            assertNull(groupNameFor("Sample.kt"))
            assertEquals(0, groupCount())
        }
    }

    /**
     * Removing a member that is not the last one leaves the group populated, so nothing is offered
     * for deletion. The absence of the prompt is asserted rather than assumed — and a wrongly
     * triggered one cannot slip past either way: the prompt is a modal opened from the same
     * runnable that clears the assignment, so it would either be caught by [hasDialog] here or
     * stall the `groupNameFor` round trip above into a timeout.
     */
    @Test
    fun `Remove from Group clears an assignment made via the popup`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            openFile("Second.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Grey")
            addFileToGroup("Second.kt", "Grey")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Remove from Group")

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
            assertNull(groupNameFor("Sample.kt"))
            assertEquals("Grey", groupNameFor("Second.kt"))
            assertFalse(hasDialog("Empty Tab Group"), "a group with a member left must not be offered for deletion")
            assertEquals(1, groupCount())
        }
    }

    /**
     * Chrome's "the group goes away with its last tab", as close as a file-scoped model gets to it:
     * the prompt fires when the group's last *member* is removed — not when its last tab is closed,
     * which does not touch membership at all.
     */
    @Test
    fun `removing a group's last member offers to delete it`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Solo")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Remove from Group")

            dialog("Empty Tab Group") {
                find<JButtonFixture>(JButtonFixture.byText("Delete Group")).click()
            }

            waitFor(ofSeconds(10)) { groupCount() == 0 }
            assertNull(groupNameFor("Sample.kt"))
            assertEquals(0, groupCount())
        }
    }

    /** Declining the prompt must leave the emptied group exactly where it was. */
    @Test
    fun `keeping an emptied group leaves it in place`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Kept")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Remove from Group")

            dialog("Empty Tab Group") {
                find<JButtonFixture>(JButtonFixture.byText("Keep")).click()
            }

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
            assertEquals(1, groupCount())
        }
    }

    /**
     * The second way a group empties, and the one that is easy to miss: nothing was *removed* —
     * the group's only member was moved into a different group.
     */
    @Test
    fun `moving a group's last member elsewhere offers to delete the group it left`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            openFile("Second.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Origin")
            remoteRobot.createGroupViaPopup(this, "Second.kt", colour = "Green", name = "Target")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupSubstepItem("Add to Group…", "Target")

            dialog("Empty Tab Group") {
                find<JButtonFixture>(JButtonFixture.byText("Delete Group")).click()
            }

            waitFor(ofSeconds(10)) { groupCount() == 1 }
            assertEquals("Target", groupNameFor("Sample.kt"))
            assertEquals("Target", groupNameFor("Second.kt"))
            assertEquals(1, groupCount())
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
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "StickyGroup")

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
