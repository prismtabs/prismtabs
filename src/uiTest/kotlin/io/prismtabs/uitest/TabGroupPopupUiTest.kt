package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `Remove from Group clears an assignment made via the popup`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            resetTabGroups()
            openFile("Sample.kt")
            remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Grey", name = "Grey")

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
