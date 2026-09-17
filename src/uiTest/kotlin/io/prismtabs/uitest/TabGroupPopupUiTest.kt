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
                val message = remoteRobot.windowTexts("Empty Tab Group").joinToString(" ")
                assertTrue(
                    message.contains("\"Solo\" has no tabs left. Delete the group?"),
                    "the prompt must name the emptied group and ask about it, got: $message",
                )
                assertTrue(findAll<JButtonFixture>(JButtonFixture.byText("Keep")).isNotEmpty(), "the prompt must offer Keep")
                assertEquals(
                    "Delete Group",
                    remoteRobot.defaultButtonOf("Empty Tab Group"),
                    "Delete Group must be the default button, the one Enter presses",
                )
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

    /**
     * The gap the active-group shortcut exists to close: a file that is already open joins nothing
     * on its own, because the platform fires no `fileOpened` for a file it does not have to open —
     * so the popup offers that same assignment as one click.
     *
     * The file is opened *into* the group and then unassigned, rather than opened before the group
     * is marked active, because only the first ordering is deterministic. `fileOpened` is published
     * after the editor has been created, not during: a file opened first and a group marked active
     * a moment later leaves the event queued, and it lands with the group already active and
     * auto-joins the very file whose *not* joining is the point (measured — this test failed
     * exactly that way before it was written this way round). Auto-joining first is the barrier:
     * once the assignment is visible the event has provably been delivered, and after
     * [IdeaFrame.clearGroupFor] no second one can ever fire for that file. What remains is the
     * reported state exactly — open, unassigned, a group active, and no route back into it but
     * this row.
     */
    @Test
    fun `an already-open file joins the active group from the popup in one click`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            assignFileToNewGroup("Second.kt", "StickyGroup")
            toggleActiveGroup("StickyGroup")
            try {
                openFile("Sample.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "StickyGroup" }

                clearGroupFor("Sample.kt")
                assertNull(groupNameFor("Sample.kt"), "the file must start out unassigned")

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Add to Group \"StickyGroup\"")

                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "StickyGroup" }
                assertEquals("StickyGroup", groupNameFor("Sample.kt"))
            } finally {
                // An active group is session-scoped state that would otherwise leak into whichever
                // test runs next and opens a file — same clean-up the Phase 3 test ends on.
                toggleActiveGroup("StickyGroup")
            }
        }
    }

    /**
     * The row is offered for an unassigned file only. A file that is already in a group has the
     * same need answered twice a row below — its group named in the "In group:" caption, and
     * "Add to Group…" to move it — and auto-join, which this row stands in for, likewise never
     * moves an assigned file.
     */
    @Test
    fun `the shortcut is not offered for a file that is already in a group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            assignFileToNewGroup("Sample.kt", "StickyGroup")
            toggleActiveGroup("StickyGroup")
            try {
                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                val rows = remoteRobot.popupItemTexts()

                assertTrue(rows.contains("Add to Group…"), "expected the ordinary list step, got $rows")
                assertFalse(
                    // The named row, not the "Add to Group…" list step one row below it — hence
                    // matching on the quoted group name rather than on the shared "Add to Group".
                    rows.any { it.startsWith("""Add to Group "StickyGroup"""") },
                    "expected no active-group shortcut for an assigned file, got $rows",
                )
                Keyboard(remoteRobot).escape()
            } finally {
                toggleActiveGroup("StickyGroup")
            }
        }
    }

    /**
     * The swatch row as the user reads it: the "New Group" caption, then all eight presets in order,
     * each dot painted in its own colour for the current theme. Read off the popup's step, which is
     * what the renderer paints each row from, so a dot wired to the wrong preset fails here.
     */
    @Test
    fun `the swatch row offers all eight presets, each dot in its own colour`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")

            assertEquals("New Group", remoteRobot.popupSeparatorInsetsByCaption().first().first)
            assertEquals(
                TabGroupPalette.PRESETS.map { it.label to themedPreset(it.id) },
                remoteRobot.popupRowSwatches().take(8),
                "expected the eight presets in order, each with its own colour dot",
            )
            Keyboard(remoteRobot).escape()
        }
    }

    /**
     * No click lands in the name prompt before typing: the field has focus on open, so the keyboard
     * alone replaces the pre-filled name, and Enter accepts it.
     */
    @Test
    fun `the name prompt opens focused, so typing a name and pressing Enter creates the group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Blue")
            dialog("New Tab Group")

            waitFor(ofSeconds(10), errorMessage = "the name field never took focus: ${remoteRobot.focusOwner()}") {
                remoteRobot.focusOwner().let { it.contains("TextField") && it.endsWith("@New Tab Group") }
            }
            remoteRobot.typeOverFocusedFieldAndEnter("Typed")

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Typed" }
            assertFalse(hasDialog("New Tab Group"), "Enter must close the prompt")
            assertEquals(1, groupCount())
        }
    }

    /** The two other ways out of the name prompt besides its Cancel button, which the test above that covers. */
    @Test
    fun `Escape and the title bar's close button both cancel the name prompt`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Red")
            dialog("New Tab Group")
            Keyboard(remoteRobot).escape()
            waitFor(ofSeconds(10)) { !hasDialog("New Tab Group") }
            assertEquals(0, groupCount(), "Escape must create no group")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Green")
            dialog("New Tab Group")
            remoteRobot.closeDialogFromTitleBar("New Tab Group")
            waitFor(ofSeconds(10)) { !hasDialog("New Tab Group") }
            assertEquals(0, groupCount(), "closing the prompt's window must create no group")
            assertNull(groupNameFor("Sample.kt"))
        }
    }

    /**
     * The selected tab, coloured from its own right-click: right-clicking does not move the
     * selection, and the tab paints the group colour at full strength. Clicking away then leaves it
     * painted about halfway into the strip — the platform's blend for an unselected tab.
     *
     * Measured on the painted strip, not on the colour held for the tab: both readings share one
     * `TabInfo` colour, and only the paint tells them apart.
     */
    @Test
    fun `a group made from the selected tab paints it at full strength, and at half once another tab is selected`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Second.kt")
            openFile("Sample.kt")
            waitFor(ofSeconds(10)) { selectedTabName() == "Sample.kt" }
            val strip = paintedTabRgb("Second.kt")
            val blue = themedPreset("blue")

            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Selected")

            assertEquals("Sample.kt", selectedTabName(), "right-clicking the tab must not change the selection")
            waitFor(ofSeconds(10), errorMessage = "expected ${blue.hex()} at full strength, got ${paintedTabRgb("Sample.kt").hex()}") {
                rgbClose(blue, paintedTabRgb("Sample.kt"))
            }

            selectTab("Second.kt")
            waitFor(ofSeconds(10)) { selectedTabName() == "Second.kt" }
            waitFor(ofSeconds(10), errorMessage = "expected ${blue.hex()} dimmed into ${strip.hex()} once unselected, got ${paintedTabRgb("Sample.kt").hex()}") {
                isDimmedBlend(blue, strip, paintedTabRgb("Sample.kt"))
            }
        }
    }

    @Test
    fun `a group made from a background tab leaves the selection alone and paints that tab at half strength`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            openFile("Second.kt")
            openFile("Third.kt")
            waitFor(ofSeconds(10)) { selectedTabName() == "Third.kt" }
            val strip = paintedTabRgb("Second.kt")

            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Background")

            assertEquals("Third.kt", selectedTabName(), "assigning a background tab must not select it")
            val blue = themedPreset("blue")
            waitFor(ofSeconds(10), errorMessage = "expected ${blue.hex()} dimmed into ${strip.hex()}, got ${paintedTabRgb("Sample.kt").hex()}") {
                isDimmedBlend(blue, strip, paintedTabRgb("Sample.kt"))
            }
            assertTrue(rgbClose(strip, paintedTabRgb("Second.kt")), "the untouched tab must keep the strip's colour")
        }
    }

    /**
     * "New Group" means new: the same dot accepted as-is on two files gives two groups that share a
     * name — nothing is renumbered and nothing is merged.
     */
    @Test
    fun `accepting the same preset on two files makes two groups, not one`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            openFile("Second.kt")
            listOf("Sample.kt", "Second.kt").forEach { path ->
                remoteRobot.openTabGroupPopupOn(this, path)
                remoteRobot.clickPopupItem("Blue")
                dialog("New Tab Group") { find<JButtonFixture>(JButtonFixture.byText("OK")).click() }
                waitFor(ofSeconds(10)) { groupNameFor(path) == "Blue" }
            }

            assertEquals(2, groupCount(), "the second file must start a group of its own")
            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            // Hovered with its own row as the anchor: "Blue" is also a swatch in the root list, so it
            // cannot tell the substep apart. The wait below reads the deepest list, which is the substep.
            remoteRobot.popupSubstepItems("Manage Groups…", anchor = "Manage Groups…")
            waitFor(ofSeconds(10), errorMessage = "both groups must be listed under the name each was given, got ${remoteRobot.popupItemTexts()}") {
                remoteRobot.popupItemTexts().count { it == "Blue" } == 2
            }
            repeat(2) { Keyboard(remoteRobot).escape() }
        }
    }

    @Test
    fun `Add to Group lists every group by name, each with its own colour dot`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            openFile("Second.kt")
            openFile("Third.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Red", name = "Reds")
            remoteRobot.createGroupViaPopup(this, "Second.kt", colour = "Cyan", name = "Cyans")

            remoteRobot.openTabGroupPopupOn(this, "Third.kt")
            remoteRobot.popupSubstepItems("Add to Group…", anchor = "Cyans")

            assertEquals(
                listOf("Reds" to themedPreset("red"), "Cyans" to themedPreset("cyan")),
                remoteRobot.popupRowSwatches(),
            )
            repeat(2) { Keyboard(remoteRobot).escape() }
        }
    }

    @Test
    fun `picking a group from Add to Group tints the tab at once`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            openFile("Second.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Pink", name = "Pinks")

            remoteRobot.openTabGroupPopupOn(this, "Second.kt")
            remoteRobot.clickPopupSubstepItem("Add to Group…", "Pinks")

            val pink = themedPreset("pink")
            waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Pinks" }
            waitFor(ofSeconds(10), errorMessage = "the strip never took the group's colour for the tab") { tabRgb("Second.kt") == pink }
            waitFor(ofSeconds(10), errorMessage = "the selected tab never painted ${pink.hex()}") {
                rgbClose(pink, paintedTabRgb("Second.kt"))
            }
        }
    }

    @Test
    fun `with no group active the one-click row is not offered`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            assignFileToNewGroup("Second.kt", "Idle")
            openFile("Sample.kt")
            assertNull(activeGroupName(), "precondition: no group is active")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            val rows = remoteRobot.popupItemTexts()

            assertTrue(rows.contains("Add to Group…"), "expected the ordinary list step, got $rows")
            assertFalse(rows.any { it.startsWith("Add to Group \"") }, "no active group, so no shortcut row, got $rows")
            Keyboard(remoteRobot).escape()
        }
    }

    /**
     * Removal from both sides: before, the popup names the group and offers the removal; after, the
     * tab holds no colour of its own, and the popup is back to offering a new group as if the file had
     * never been in one.
     */
    @Test
    fun `after Remove from Group the tab loses its colour and the popup offers a new group again`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Second.kt")
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Yellow", name = "Yellows")
            addFileToGroup("Second.kt", "Yellows")
            waitFor(ofSeconds(10)) { tabRgb("Sample.kt") == themedPreset("yellow") }

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            assertTrue(remoteRobot.popupSeparatorInsetsByCaption().any { it.first == "In group: Yellows" })
            assertTrue(remoteRobot.popupItemTexts().contains("Remove from Group"))
            remoteRobot.clickPopupItem("Remove from Group")

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
            waitFor(ofSeconds(10), errorMessage = "the tab kept a colour after removal") { tabRgb("Sample.kt") == null }
            assertFalse(hasDialog("Empty Tab Group"), "a group with a member left must not be offered for deletion")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            val captions = remoteRobot.popupSeparatorInsetsByCaption().map { it.first }
            val rows = remoteRobot.popupItemTexts()
            assertTrue(captions.none { it.startsWith("In group:") }, "no group left to name, got $captions")
            assertFalse(rows.contains("Remove from Group"), "nothing left to remove, got $rows")
            assertEquals(TabGroupPalette.PRESETS.map { it.label }, remoteRobot.popupRowSwatches().take(8).map { it.first })
            Keyboard(remoteRobot).escape()
        }
    }

    /**
     * Rename, keyboard only: the field comes up holding the current name and focused, Enter applies
     * the new one, every member follows — the caption over "Remove from Group" included — and no
     * colour moves.
     */
    @Test
    fun `Rename starts from the current name, and Enter renames every member without recolouring`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            openFile("Second.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Green", name = "Before")
            remoteRobot.openTabGroupPopupOn(this, "Second.kt")
            remoteRobot.clickPopupSubstepItem("Add to Group…", "Before")
            waitFor(ofSeconds(10)) { tabRgb("Second.kt") == themedPreset("green") }

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Before", "Rename…")
            dialog("Rename Tab Group") {
                assertEquals("Before", find<JTextFieldFixture>(JTextFieldFixture.byType()).text)
            }
            waitFor(ofSeconds(10), errorMessage = "the rename field never took focus: ${remoteRobot.focusOwner()}") {
                remoteRobot.focusOwner().let { it.contains("TextField") && it.endsWith("@Rename Tab Group") }
            }
            remoteRobot.typeOverFocusedFieldAndEnter("After")

            waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "After" && groupNameFor("Second.kt") == "After" }
            val green = themedPreset("green")
            assertEquals(green, tabRgb("Sample.kt"), "renaming must not recolour a member")
            assertEquals(green, tabRgb("Second.kt"), "renaming must not recolour a member")

            remoteRobot.openTabGroupPopupOn(this, "Second.kt")
            assertTrue(
                remoteRobot.popupSeparatorInsetsByCaption().any { it.first == "In group: After" },
                "the caption must carry the new name",
            )
            Keyboard(remoteRobot).escape()
        }
    }

    @Test
    fun `cancelling Rename keeps the old name, whatever was typed`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Settled")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Settled", "Rename…")
            dialog("Rename Tab Group") {
                find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "Discarded"
                find<JButtonFixture>(JButtonFixture.byText("Cancel")).click()
            }

            waitFor(ofSeconds(10)) { !hasDialog("Rename Tab Group") }
            assertEquals("Settled", groupNameFor("Sample.kt"))
        }
    }

    /**
     * Recolour's list is the eight presets with their dots, and a pick applies there and then — to
     * the open member's tab, and to a member with no tab at all, which shows the new colour the moment
     * it is opened.
     */
    @Test
    fun `Recolour offers the eight presets and repaints every member, open or not`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Paint")
            addFileToGroup("Third.kt", "Paint")
            assertFalse(openTabNames().contains("Third.kt"), "precondition: one member has no tab")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.popupSubstepItems("Paint", anchor = "Recolour…")
            // Every preset label is also a swatch in the root list, so none can anchor the recolour
            // step; the wait reads the deepest list, which is that step once it has opened.
            remoteRobot.popupSubstepItems("Recolour…", anchor = "Recolour…")
            val presets = TabGroupPalette.PRESETS.map { it.label to themedPreset(it.id) }
            waitFor(ofSeconds(10), errorMessage = "expected the recolour step's eight presets, got ${remoteRobot.popupRowSwatches()}") {
                remoteRobot.popupRowSwatches() == presets
            }
            remoteRobot.clickPopupItem("Purple")

            val purple = TabGroupPalette.PRESETS.first { it.id == "purple" }
            waitFor(ofSeconds(10)) { groupLightRgbFor("Sample.kt") == purple.light.rgb and 0xFFFFFF }
            assertFalse(hasDialog("Recolour"), "recolouring must not ask for confirmation")
            assertEquals(purple.light.rgb and 0xFFFFFF, groupLightRgbFor("Third.kt"))
            assertEquals(purple.dark.rgb and 0xFFFFFF, groupDarkRgbFor("Third.kt"))
            waitFor(ofSeconds(10), errorMessage = "the open member's tab was not repainted") { tabRgb("Sample.kt") == themedPreset("purple") }

            openFile("Third.kt")
            waitFor(ofSeconds(10), errorMessage = "the member opened afterwards did not show the new colour") {
                runCatching { tabRgb("Third.kt") == themedPreset("purple") }.getOrDefault(false)
            }
        }
    }

    @Test
    fun `marking a group active turns its item into Clear Active Group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "Flag")
                openFile("Sample.kt")

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("Flag", "Mark as Active Group")
                waitFor(ofSeconds(10)) { activeGroupName() == "Flag" }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                val items = remoteRobot.popupSubstepItems("Flag (active)", anchor = "Delete Group")
                assertTrue(items.contains("Clear Active Group"), "expected the item to offer clearing, got $items")
                assertFalse(items.contains("Mark as Active Group"), "the marking item must be gone, got $items")
                repeat(3) { Keyboard(remoteRobot).escape() }
            } finally {
                clearActiveGroup()
            }
        }
    }

    @Test
    fun `marking a second group active clears the first`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "Alpha")
                assignFileToNewGroup("Second.kt", "Beta")
                openFile("Sample.kt")

                listOf("Alpha", "Beta").forEach { group ->
                    remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                    remoteRobot.clickPopupItem("Manage Groups…")
                    remoteRobot.clickPopupSubstepItem(group, "Mark as Active Group")
                    waitFor(ofSeconds(10)) { activeGroupName() == group }
                }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                val listed = remoteRobot.popupSubstepItems("Manage Groups…", anchor = "Beta (active)")
                assertTrue(listed.contains("Alpha"), "Alpha must no longer be marked active, got $listed")
                assertFalse(listed.contains("Alpha (active)"), "only one group may be active, got $listed")
                repeat(2) { Keyboard(remoteRobot).escape() }

                openFile("Third.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "Beta" }
            } finally {
                clearActiveGroup()
            }
        }
    }

    /**
     * Auto-join end to end, both directions, through the popup items a user uses: a file opened while
     * the group is active joins it and its tab takes the colour; once cleared, the next file opened
     * joins nothing.
     */
    @Test
    fun `a file opened while a group is active joins and takes its colour, and none does once it is cleared`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "Auto", lightRgb = 0xAECBFA, darkRgb = 0x2E5C9C)
                openFile("Sample.kt")

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("Auto", "Mark as Active Group")
                waitFor(ofSeconds(10)) { activeGroupName() == "Auto" }

                openFile("Second.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Auto" }
                waitFor(ofSeconds(10), errorMessage = "the auto-joined tab never took the group's colour") {
                    runCatching { tabRgb("Second.kt") == themed(0xAECBFA, 0x2E5C9C) }.getOrDefault(false)
                }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("Auto (active)", "Clear Active Group")
                waitFor(ofSeconds(10)) { activeGroupName() == null }

                openFile("Third.kt")
                waitFor(ofSeconds(5)) { openTabNames().contains("Third.kt") }
                assertNull(groupNameFor("Third.kt"), "a file opened after clearing must not join any group")
            } finally {
                clearActiveGroup()
            }
        }
    }

    /**
     * The file list's shape: titled after its group, rows ordered by directory and then name, each
     * row the file's name followed by its project-relative directory in grey.
     */
    @Test
    fun `the file list is titled after its group and sorts its greyed directories`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                silenceAddToVcsConfirmation()
                resetTabGroups()
                hideTabGroupsToolWindow()
                createFixtureFile("files-list/beta/Alpha.txt")
                createFixtureFile("files-list/alpha/Zeta.txt")
                createFixtureFile("files-list/alpha/Beta.txt")
                openFile("Sample.kt")
                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Sorted")
                listOf("files-list/beta/Alpha.txt", "files-list/alpha/Zeta.txt", "files-list/alpha/Beta.txt")
                    .forEach { addFileToGroup(it, "Sorted") }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("Sorted", "Files…")
                remoteRobot.chooserPopupRows()

                assertTrue(remoteRobot.windowTexts().contains("Files in Sorted"), "the list must be titled after its group")
                assertEquals(
                    listOf(
                        "Sample.kt{plain}",
                        "Beta.txt{plain}¦  files-list/alpha{grey}",
                        "Zeta.txt{plain}¦  files-list/alpha{grey}",
                        "Alpha.txt{plain}¦  files-list/beta{grey}",
                    ),
                    remoteRobot.chooserRowFragments(),
                )
                Keyboard(remoteRobot).escape()
            } finally {
                deleteFixtureFile("files-list")
            }
        }
    }

    @Test
    fun `choosing a member that is already open in the file list selects its tab rather than opening another`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            resetTabGroups()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            openFile("Second.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Red", name = "Focus")
            addFileToGroup("Second.kt", "Focus")
            waitFor(ofSeconds(10)) { selectedTabName() == "Second.kt" }

            remoteRobot.openTabGroupPopupOn(this, "Second.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Focus", "Files…")
            remoteRobot.clickChooserPopupItem("Sample.kt")

            waitFor(ofSeconds(10)) { selectedTabName() == "Sample.kt" }
            assertEquals(listOf("Sample.kt", "Second.kt"), openTabNames().sorted(), "no second tab may open for the same file")
        }
    }

    /**
     * Delete Group from the popup asks first, with concrete buttons — Delete and Cancel, not a bare
     * Yes/No — and Cancel changes nothing.
     */
    @Test
    fun `Delete Group from the popup asks with Delete and Cancel, and Cancel keeps the group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            resetTabGroupPalette()
            hideTabGroupsToolWindow()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Purple", name = "Keeper")

            remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
            remoteRobot.clickPopupItem("Manage Groups…")
            remoteRobot.clickPopupSubstepItem("Keeper", "Delete Group")
            dialog("Delete Tab Group") {
                assertTrue(findAll<JButtonFixture>(JButtonFixture.byText("Delete")).isNotEmpty(), "expected a Delete button")
                assertTrue(findAll<JButtonFixture>(JButtonFixture.byText("Yes")).isEmpty(), "no generic Yes button")
                find<JButtonFixture>(JButtonFixture.byText("Cancel")).click()
            }

            waitFor(ofSeconds(10)) { !hasDialog("Delete Tab Group") }
            assertEquals("Keeper", groupNameFor("Sample.kt"))
            assertEquals(1, groupCount())
            assertEquals(themedPreset("purple"), tabRgb("Sample.kt"), "a cancelled delete must leave the tint alone")
        }
    }

    @Test
    fun `confirming Delete Group untints every member and drops the group from the popup and the tool window`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                openFile("Sample.kt")
                openFile("Second.kt")
                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Cyan", name = "Doomed")
                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupSubstepItem("Add to Group…", "Doomed")
                assignFileToNewGroup("Third.kt", "Other")
                waitFor(ofSeconds(10)) { tabRgb("Second.kt") == themedPreset("cyan") }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("Doomed", "Delete Group")
                dialog("Delete Tab Group") { find<JButtonFixture>(JButtonFixture.byText("Delete")).click() }

                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null && groupNameFor("Second.kt") == null }
                waitFor(ofSeconds(10), errorMessage = "a deleted group's members kept their tint") {
                    tabRgb("Sample.kt") == null && tabRgb("Second.kt") == null
                }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                val listed = remoteRobot.popupSubstepItems("Manage Groups…", anchor = "Other")
                assertTrue(listed.none { it.startsWith("Doomed") }, "the popup must no longer list it, got $listed")
                repeat(2) { Keyboard(remoteRobot).escape() }

                val rows = remoteRobot.toolWindowGroupRows()
                assertTrue(rows.none { it.contains("Doomed") } && rows.any { it.contains("Other") }, "got $rows")
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The one delete that loses more than an assignment: a hidden group takes its record of what to
     * reopen with it, and the real dialog says so — asserted on the rendered message, where
     * `DeleteConfirmationMessageTest` can only pin the string.
     */
    @Test
    fun `deleting a hidden group warns that the tabs it would reopen will be forgotten`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                hideTabGroupsToolWindow()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")
                assignFileToNewGroup("Sample.kt", "Shelved")
                addFileToGroup("Second.kt", "Shelved")
                setGroupHidden("Shelved", hidden = true)
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Third.kt") }

                remoteRobot.openTabGroupPopupOn(this, "Third.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("Shelved", "Delete Group")
                dialog("Delete Tab Group") {
                    val message = remoteRobot.windowTexts("Delete Tab Group").joinToString(" ")
                    assertTrue(message.contains("Delete \"Shelved\"? Its files will be unassigned."), "got: $message")
                    assertTrue(message.contains("This group is hidden. The 2 tabs it would reopen will be forgotten."), "got: $message")
                    find<JButtonFixture>(JButtonFixture.byText("Cancel")).click()
                }
                waitFor(ofSeconds(10)) { !hasDialog("Delete Tab Group") }
            } finally {
                setGroupHidden("Shelved", hidden = false)
            }
        }
    }

    /** The empty-group prompt's second trigger, reached through a preset dot rather than "Add to Group…". */
    @Test
    fun `starting a new group from a group's last member offers to delete the group it left`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Blue", name = "Origin")

            remoteRobot.createGroupViaPopupExpectingPrompt(this, "Sample.kt", colour = "Green", name = "Destination")
            dialog("Empty Tab Group") {
                val message = remoteRobot.windowTexts("Empty Tab Group").joinToString(" ")
                assertTrue(message.contains("\"Origin\" has no tabs left"), "the prompt must name the group left behind, got: $message")
                find<JButtonFixture>(JButtonFixture.byText("Delete Group")).click()
            }

            waitFor(ofSeconds(10)) { groupCount() == 1 }
            assertEquals("Destination", groupNameFor("Sample.kt"))
        }
    }
}

/**
 * [createGroupViaPopup] for a file that is already the last member of another group: the name prompt
 * is accepted, but the caller handles the empty-group prompt that follows, so nothing here waits on
 * the assignment — it only lands once that modal is answered.
 */
private fun RemoteRobot.createGroupViaPopupExpectingPrompt(ideaFrame: IdeaFrame, path: String, colour: String, name: String) {
    openTabGroupPopupOn(ideaFrame, path)
    clickPopupItem(colour)
    ideaFrame.dialog("New Tab Group") {
        find<JTextFieldFixture>(JTextFieldFixture.byType()).text = name
        find<JButtonFixture>(JButtonFixture.byText("OK")).click()
    }
}
