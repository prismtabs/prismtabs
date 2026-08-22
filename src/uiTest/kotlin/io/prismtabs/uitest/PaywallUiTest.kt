package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * The free tier, end-to-end: what an unsubscribed user actually gets once PrismTabs is a paid
 * plugin (Freemium + Yearly Subscription — see the README's "Marketplace publishing prep").
 *
 * These are the only tests that exercise the locked side of the four paid-feature gates. Every
 * *other* uiTest here runs on an unlocked build and always will, because
 * `PrismTabsLicense.paidFeaturesEnabled()` returns `true` outright while `plugin.xml` carries no
 * `<product-descriptor>` — so without [IdeaFrame.simulateUnlicensedPaidBuild] the paywall would
 * have no automated coverage at all, in this suite or any other.
 *
 * Two conventions worth knowing before adding to this class:
 *  - **Every test asserts [IdeaFrame.paidFeaturesEnabled] is `false` before asserting anything
 *    else.** A simulation that silently stopped working would otherwise turn every assertion below
 *    into a re-test of the unlocked path that still passes.
 *  - **Locked features are driven through their own choke points, not clicked.** Same trade-off
 *    [IdeaFrame.setGroupCollapsed] documents — and here it additionally avoids the registration
 *    dialog a gated *click* would open, which is why [IdeaFrame.suppressRegisterDialog] backs it up.
 */
@ExtendWith(RemoteRobotExtension::class)
class PaywallUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `the tool window is replaced by an upsell, and comes back when the licence does`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                assignFileToNewGroup("Sample.kt", "Paywalled")

                // Unlocked first — the same window this test is about to see locked really does
                // list groups, so a later "no rows" assertion means the paywall, not an empty model.
                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("Paywalled") } }
                assertEquals("", toolWindowEmptyText(), "an unlocked tool window shows the list, not an empty state")

                simulateUnlicensedPaidBuild()
                assertFalse(paidFeaturesEnabled(), "the simulated paid build should read as unlicensed")

                // Hidden, then shown — not show() on an already-visible window, which fires no
                // toolWindowShown and would leave the panel on its last-read licence state. That is
                // the real product behaviour and this test asserts around it deliberately: a licence
                // changing under an open tool window is picked up the next time it's opened (or on
                // the next group change), not the instant it changes, because nothing in the
                // platform broadcasts a licence change to subscribe to. No IDE restart is involved.
                hideTabGroupsToolWindow()
                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { toolWindowEmptyText().contains("paid feature") }

                val locked = toolWindowEmptyText()
                assertTrue(
                    locked.contains("Tab Groups tool window is a PrismTabs paid feature"),
                    "expected the locked empty state to name the feature, got: $locked",
                )
                assertTrue(
                    locked.contains("right-click menu stays free"),
                    "the locked state must say what the free tier still does, got: $locked",
                )
                assertTrue(
                    locked.contains("Start the free trial"),
                    "the locked state must offer a way to subscribe, got: $locked",
                )

                restoreUnpaidBuild()
                assertTrue(paidFeaturesEnabled(), "restoring the unpaid build should reopen the gate")

                // Hidden then shown again, for the same reason as above — this window is currently
                // visible showing the upsell, so a bare show() would fire nothing to re-read on.
                hideTabGroupsToolWindow()
                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { toolWindowEmptyText() == "" }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("Paywalled") },
                    "the group list must return once the plugin is licensed again",
                )
            } finally {
                restoreUnpaidBuild()
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The one paywall behaviour that isn't about withholding anything: collapsing closes tabs and
     * only the tool window can reopen them, so a lapse with a group still collapsed must not leave
     * those tabs shut behind the very window that closed them.
     */
    @Test
    fun `a lapsed subscription can still reopen the tabs of a collapsed group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Stranded")
                addFileToGroup("Second.kt", "Stranded")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Stranded" }

                // Collapse while still entitled to, then lose the entitlement — the exact sequence
                // that strands tabs.
                setGroupCollapsed("Stranded", collapsed = true)
                waitFor(ofSeconds(10)) { !openTabNames().contains("Sample.kt") && !openTabNames().contains("Second.kt") }

                simulateUnlicensedPaidBuild()
                assertFalse(paidFeaturesEnabled(), "the simulated paid build should read as unlicensed")

                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { toolWindowEmptyText().contains("paid feature") }
                assertTrue(
                    toolWindowEmptyText().contains("Reopen the tabs of 1 hidden group"),
                    "a locked window with a collapsed group must offer the escape hatch, got: ${toolWindowEmptyText()}",
                )

                expandAllCollapsedGroups()

                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt")) }
                assertEquals(
                    listOf("Sample.kt", "Second.kt"),
                    openTabNames().filter { it == "Sample.kt" || it == "Second.kt" },
                    "the stranded tabs must reopen in their recorded order, paywall or not",
                )

                // ...and the offer withdraws itself once there's nothing left to rescue, rather than
                // becoming a permanently free collapse/expand button.
                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { !toolWindowEmptyText().contains("Reopen the tabs") }
                assertTrue(
                    toolWindowEmptyText().contains("paid feature"),
                    "the window stays locked after the rescue, got: ${toolWindowEmptyText()}",
                )
            } finally {
                restoreUnpaidBuild()
                hideTabGroupsToolWindow()
            }
        }
    }

    @Test
    fun `an active group stops auto-joining newly opened files once the licence lapses`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                assignFileToNewGroup("Sample.kt", "Sticky")

                toggleActiveGroup("Sticky")
                assertEquals("Sticky", activeGroupName(), "marking active should work while unlicensed builds are ungated")

                openFile("Second.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Sticky" }

                // The group stays active across the lapse — it's session state, not a licence check
                // — so this proves the listener itself declines, not that the setup was undone.
                simulateUnlicensedPaidBuild()
                assertFalse(paidFeaturesEnabled(), "the simulated paid build should read as unlicensed")
                assertEquals("Sticky", activeGroupName(), "the active group should survive the lapse untouched")

                openFile("Third.kt")
                waitFor(ofSeconds(5)) { openTabNames().contains("Third.kt") }
                assertNull(
                    groupNameFor("Third.kt"),
                    "a file opened after the lapse must not auto-join the still-active group",
                )

                // Clearing an active group stays free: never strand a user in a state only a
                // subscription can undo.
                suppressRegisterDialog()
                toggleActiveGroup("Sticky")
                assertNull(activeGroupName(), "clearing the active group must stay free for a lapsed subscriber")

                // ...whereas re-marking one is refused.
                toggleActiveGroup("Sticky")
                assertNull(activeGroupName(), "marking a group active must be refused while unlicensed")
            } finally {
                restoreUnpaidBuild()
                restoreRegisterDialog()
                closeAllEditors()
            }
        }
    }

    @Test
    fun `the palette settings page is read-only and refuses to persist edits when locked`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroupPalette()
                simulateUnlicensedPaidBuild()
                assertFalse(paidFeaturesEnabled(), "the simulated paid build should read as unlicensed")

                val checkpoints = exercisePaletteConfigurableLocked().associate {
                    val (label, value) = it.split(":", limit = 2)
                    label to value
                }

                assertEquals("false", checkpoints["fieldEnabled"], "the preset name fields must come up disabled when locked")
                assertEquals(
                    "Grey",
                    checkpoints["serviceLabelAfterApply"],
                    "an apply() forced past the disabled fields must not reach TabGroupPaletteService",
                )
                assertEquals(
                    "false",
                    checkpoints["modifiedAfterApply"],
                    "the discarded edit must leave the page settled, not permanently modified",
                )
            } finally {
                restoreUnpaidBuild()
                resetTabGroupPalette()
            }
        }
    }

}
