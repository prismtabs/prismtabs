package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
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
