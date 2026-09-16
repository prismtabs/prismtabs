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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * The free tier, end-to-end: what an unsubscribed user actually gets once PrismTabs is a paid
 * plugin (Freemium + Yearly Subscription — see the README's "Marketplace publishing prep").
 *
 * These are the only tests that exercise the locked side of the four paid-feature gates. Every
 * *other* uiTest here runs entitled — [RemoteRobotExtension] installs that state before each test
 * (see [IdeaFrame.simulateEntitled] for what "entitled" can and cannot stand in for) — so without
 * [IdeaFrame.simulateUnlicensed] the paywall would have no automated coverage at all, in this suite
 * or any other.
 *
 * Four conventions worth knowing before adding to this class:
 *  - **Every test asserts [IdeaFrame.paidFeaturesEnabled] is `false` before asserting anything
 *    else.** A simulation that silently stopped working would otherwise turn every assertion below
 *    into a re-test of the unlocked path that still passes.
 *  - **Every test that locks must unlock again in a `finally`.** The extension re-entitles before
 *    the *next* test, so a leak is no longer catastrophic, but leaving the sandbox locked still
 *    breaks the rest of the current test's own cleanup.
 *  - **Locked features are driven through their own choke points, not clicked** — and specifically
 *    through the choke point that *carries the gate*, never past it. [IdeaFrame.setGroupHidden]
 *    reaches `TabGroupVisibility` directly and so proves nothing about the paywall;
 *    [IdeaFrame.invokeToggleVisibility] is its gated counterpart and is what a hide/show test must
 *    use. Same trade-off [IdeaFrame.setGroupHidden] documents — and here it additionally avoids
 *    the registration dialog a gated *click* would open, which is why
 *    [IdeaFrame.suppressRegisterDialog] backs it up.
 *  - **The free-tier test is the exception to the one above, and must stay that way.** Every gate
 *    lives in the UI layer, never in `TabGroupService`, so a test that the free tier still *works*
 *    has to click the real popup: reflecting into the service would route past the only layer a
 *    `requirePaidFeature` could be added to, and would pass regardless. That one test therefore
 *    needs a real `java.awt.Robot` and an X11 display; the rest of this class does not.
 */
@ExtendWith(RemoteRobotExtension::class)
class PaywallUiTest {
    init {
        StepsLogger.init()
    }

    /**
     * Every test here is about a *paid build's* locked side, and only a paid build has one: with
     * `<product-descriptor>` commented out — as [PluginUnloadUiTest] asks, to exercise an in-place
     * unload — [IdeaFrame.simulateUnlicensed] has nothing to lock, so each test fails on its first
     * locked assertion. Worse, the failures do not stay here: measured on 2026-09-16, a full run on
     * that build also failed ten tool-window and popup tests that pass in the same run without this
     * class. Skipped instead, the mirror image of [PluginUnloadUiTest]'s own gate.
     *
     * A `@BeforeEach` rather than a line in each test because answering it means asking the running
     * IDE, and all eleven need the same answer.
     */
    @BeforeEach
    fun requirePaidBuild(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            assumeTrue(isPaidBuild(), "the paywall exists only on a build with a <product-descriptor>")
        }
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

                simulateUnlicensed()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

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
                    locked.contains("The PrismTabs tool window is a paid feature"),
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

                simulateEntitled()
                assertTrue(paidFeaturesEnabled(), "restoring the subscription should reopen the gate")

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
                simulateEntitled()
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The one paywall behaviour that isn't about withholding anything: hiding closes tabs and
     * only the tool window can reopen them, so a lapse with a group still hidden must not leave
     * those tabs shut behind the very window that closed them.
     */
    @Test
    fun `a lapsed subscription can still reopen the tabs of a hidden group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Stranded")
                addFileToGroup("Second.kt", "Stranded")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Stranded" }

                // Hide while still entitled to, then lose the entitlement — the exact sequence
                // that strands tabs.
                setGroupHidden("Stranded", hidden = true)
                waitFor(ofSeconds(10)) { !openTabNames().contains("Sample.kt") && !openTabNames().contains("Second.kt") }

                simulateUnlicensed()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { toolWindowEmptyText().contains("paid feature") }
                assertTrue(
                    toolWindowEmptyText().contains("Reopen the tabs of 1 hidden group"),
                    "a locked window with a hidden group must offer the escape hatch, got: ${toolWindowEmptyText()}",
                )

                showAllHiddenGroups()

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
                simulateEntitled()
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
                assertEquals("Sticky", activeGroupName(), "marking a group active must work while subscribed")

                openFile("Second.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Sticky" }

                // The group stays active across the lapse — it's session state, not a licence check
                // — so this proves the listener itself declines, not that the setup was undone.
                simulateUnlicensed()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")
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

                // The purchase direction, which nothing else covers: everything above would still
                // pass if the gate had jammed shut, so the subscription is restored and the same
                // two calls re-run. Auto-join in particular is read per file-open with no cached
                // state, and this is what proves it.
                simulateEntitled()
                assertTrue(paidFeaturesEnabled(), "restoring the subscription should reopen the gate")

                toggleActiveGroup("Sticky")
                assertEquals("Sticky", activeGroupName(), "marking a group active must work again once subscribed")

                // Closed and reopened, not just opened: auto-join hangs off `fileOpened`, and
                // openFile on an already-open tab only selects it, firing nothing to join on.
                closeAllEditors()
                openFile("Third.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "Sticky" }
            } finally {
                simulateEntitled()
                restoreRegisterDialog()
                closeAllEditors()
            }
        }
    }

    /**
     * The hide/show gate on its own call site, which nothing else here reaches.
     *
     * The scenario is the one that makes that gate necessary rather than redundant with the locked
     * panel simply not drawing a toolbar: the tool window is left **open on its unlocked rendering**
     * when the licence lapses. Nothing broadcasts a licence change (see `PrismTabsLicense`), so the
     * panel keeps its list, its toolbar and its double-click handler until something makes it
     * refresh — and every one of those routes lands on `toggleVisibility`. This test therefore
     * deliberately does *not* re-show the tool window after the lapse: re-showing is what the first
     * test in this class covers, and doing it here would repaint the panel into its locked state and
     * quietly retire the very reachability being asserted.
     */
    @Test
    fun `hiding and showing are refused from a tool window left open when the licence lapses`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Gated")
                addFileToGroup("Second.kt", "Gated")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Gated" }

                // Shown while still entitled, and left that way for the rest of the test.
                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("Gated") } }

                simulateUnlicensed()
                suppressRegisterDialog()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

                // Hiding: refused, so the tabs stay open and the group stays shown.
                invokeToggleVisibility("Gated")
                assertFalse(isGroupHidden("Gated"), "a gated hide must not leave the group reading as hidden")
                assertTrue(
                    openTabNames().containsAll(listOf("Sample.kt", "Second.kt")),
                    "a gated hide must not close the group's tabs, got: ${openTabNames()}",
                )

                // Showing is gated too, and proving it needs a group that is already hidden while
                // unlicensed. setGroupHidden drives TabGroupVisibility directly — past the gate on
                // purpose — which is exactly the state a lapse mid-collapse leaves behind.
                setGroupHidden("Gated", hidden = true)
                waitFor(ofSeconds(10)) { !openTabNames().contains("Sample.kt") && !openTabNames().contains("Second.kt") }

                invokeToggleVisibility("Gated")
                assertTrue(isGroupHidden("Gated"), "a gated show must leave the group hidden")
                assertFalse(
                    openTabNames().contains("Sample.kt"),
                    "a gated show must not reopen the tabs — that is the escape hatch's job, got: ${openTabNames()}",
                )

                // The assertions above are all "nothing happened", which a helper that silently did
                // nothing would also satisfy. Restoring the licence and re-running the *same* call
                // is what proves they were the paywall refusing rather than the test misfiring.
                simulateEntitled()
                assertTrue(paidFeaturesEnabled(), "restoring the subscription should reopen the gate")

                invokeToggleVisibility("Gated")
                waitFor(ofSeconds(10)) { !isGroupHidden("Gated") }
                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt")) }
            } finally {
                simulateEntitled()
                showAllHiddenGroups()
                restoreRegisterDialog()
                hideTabGroupsToolWindow()
                closeAllEditors()
            }
        }
    }

    /**
     * The other half of the paywall, and the half a gate-by-gate test can never show: that the
     * **free tier still works**. Every other test in this class asserts something is withheld, so a
     * regression that over-gated — a `requirePaidFeature` added to the popup's swatch handler, say —
     * would make all of them pass harder while breaking the product for every unsubscribed user.
     *
     * What it covers is not arbitrary: it is what the README's "Marketplace publishing prep" section
     * sells as free (manual colouring, unlimited groups), and what the locked tool window's own
     * upsell promises in as many words — "Colouring tabs and managing groups from a tab's
     * right-click menu stays free". The first test in this class asserts that sentence is
     * *displayed*; this one asserts it is *true*.
     *
     * **The one mouse-driven test in this class**, and deliberately so — it is the whole point of
     * it. Every gate lives in the UI layer ([io.prismtabs.ui.TabGroupPopup] and friends), never in
     * `TabGroupService`, so the script-driven [IdeaFrame.assignFileToNewGroup] shortcut the other
     * tests use reflects straight past the only layer that *could* be gated: a `requirePaidFeature`
     * added to the popup would leave it passing. Driving the real popup is therefore load-bearing
     * here, at the cost of needing an X11 display like the rest of the suite (see README "These
     * tests need an X11 display") — unlike its Wayland-safe siblings above.
     *
     * [IdeaFrame.suppressRegisterDialog] is held for the whole test even though nothing here should
     * open it: if a free path *has* been gated, the upsell is what a refusal raises, and a modal
     * left open on the sandbox wedges robot-server. Suppressed, that regression fails as a clean
     * assertion instead of a hang.
     */
    @Test
    fun `the free tier still colours tabs and manages groups while the licence is lapsed`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                closeAllEditors()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")

                simulateUnlicensed()
                installRegisterProbe()
                hideTabGroupsToolWindow()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

                // Creating a group by clicking a colour dot: the popup's own flow, modal name prompt
                // included, all while unlicensed.
                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Green", name = "FreeGroup")
                assertEquals("FreeGroup", groupNameFor("Sample.kt"), "creating a group must stay free")
                assertEquals(
                    0xA8DAB5,
                    groupLightRgbFor("Sample.kt"),
                    "the chosen preset's colour must actually be applied — manual colouring is free",
                )

                // "Unlimited groups" is a free-tier promise, so a *second* group must be creatable:
                // a quantity-based cap is the most obvious wrong way to build this free tier, and it
                // would be caught here and nowhere else.
                remoteRobot.createGroupViaPopup(this, "Second.kt", colour = "Blue", name = "AlsoFree")
                assertEquals(2, groupCount(), "a lapsed subscriber must still be able to hold multiple groups")

                // "Add to Group…" — the popup's other free choke point, and a separate handler from
                // the swatch click above, so gating one would not fail the assertions before this.
                // Third.kt is ungrouped, so nothing is emptied and no "Empty Tab Group" modal opens.
                remoteRobot.openTabGroupPopupOn(this, "Third.kt")
                remoteRobot.clickPopupSubstepItem("Add to Group…", "FreeGroup")
                waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == "FreeGroup" }

                // Nothing the free tier did may have closed a tab: hiding is the paid feature, and
                // grouping must never imply it.
                assertTrue(
                    openTabNames().containsAll(listOf("Sample.kt", "Second.kt", "Third.kt")),
                    "grouping while locked must leave every tab open, got: ${openTabNames()}",
                )
                assertFalse(isGroupHidden("FreeGroup"), "a group created while locked must not come up hidden")

                // The rest of "managing groups from a tab's right-click menu": rename, recolour, the
                // file list, removal and deletion, each through the popup a lapsed user has.
                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("FreeGroup", "Rename…")
                dialog("Rename Tab Group") {
                    find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "RenamedFree"
                    find<JButtonFixture>(JButtonFixture.byText("OK")).click()
                }
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "RenamedFree" }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.popupSubstepItems("RenamedFree", anchor = "Recolour…")
                remoteRobot.popupSubstepItems("Recolour…", anchor = "Recolour…")
                waitFor(ofSeconds(10)) { remoteRobot.popupRowSwatches().size == 8 }
                remoteRobot.clickPopupItem("Purple")
                waitFor(ofSeconds(10)) { groupLightRgbFor("Sample.kt") == 0xD3B3F5 }

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("RenamedFree", "Files…")
                waitFor(ofSeconds(10)) { remoteRobot.chooserPopupRows().size == 2 }
                Keyboard(remoteRobot).escape()

                remoteRobot.openTabGroupPopupOn(this, "Third.kt")
                remoteRobot.clickPopupItem("Remove from Group")
                waitFor(ofSeconds(10)) { groupNameFor("Third.kt") == null }

                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.clickPopupSubstepItem("AlsoFree", "Delete Group")
                dialog("Delete Tab Group") { find<JButtonFixture>(JButtonFixture.byText("Delete")).click() }
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == null }

                assertEquals(
                    emptyList<String>(),
                    registerProbeMessages(),
                    "nothing the free tier does may raise the subscription dialog",
                )
            } finally {
                simulateEntitled()
                removeRegisterProbe()
                closeAllEditors()
            }
        }
    }

    /**
     * Every one of the eight presets still starts a coloured group for a lapsed subscriber. Each new
     * group is made from the same tab, so each one after the first empties the previous group, whose
     * prompt is answered along the way.
     */
    @Test
    fun `every preset still colours a new group while the licence is lapsed`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                openFile("Sample.kt")
                simulateUnlicensed()
                installRegisterProbe()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

                TabGroupPalette.PRESETS.forEachIndexed { index, preset ->
                    remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                    remoteRobot.clickPopupItem(preset.label)
                    dialog("New Tab Group") { find<JButtonFixture>(JButtonFixture.byText("OK")).click() }
                    if (index > 0) dialog("Empty Tab Group") { find<JButtonFixture>(JButtonFixture.byText("Delete Group")).click() }

                    waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == preset.label && groupCount() == 1 }
                    assertEquals(preset.light.rgb and 0xFFFFFF, groupLightRgbFor("Sample.kt"), "${preset.label}'s light colour")
                    assertEquals(preset.dark.rgb and 0xFFFFFF, groupDarkRgbFor("Sample.kt"), "${preset.label}'s dark colour")
                    val shown = themedPreset(preset.id)
                    waitFor(ofSeconds(10), errorMessage = "${preset.label}: expected ${shown.hex()}, painted ${paintedTabRgb("Sample.kt").hex()}") {
                        rgbClose(shown, paintedTabRgb("Sample.kt"))
                    }
                }
                assertEquals(emptyList<String>(), registerProbeMessages(), "colouring tabs must never raise the subscription dialog")
            } finally {
                simulateEntitled()
                removeRegisterProbe()
            }
        }
    }

    /**
     * The sandbox is a cold start of a paid build that holds no licence — measured at boot, before
     * [RemoteRobotExtension] stands in a facade (see [IdeaFrame.simulateEntitled]) — which is the
     * exact state the "optional" product descriptor exists for. The plugin must have come up enabled
     * and loaded, and must not have been written into `disabled_plugins.txt`, where a platform-side
     * disable would keep it off across every later restart too.
     */
    @Test
    fun `a paid build started without a licence comes up enabled rather than disabled`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            assertTrue(isPaidBuild(), "this test is about the paid build; plugin.xml must carry its product-descriptor")
            val state = prismTabsPluginState()
            assertEquals(true, state["enabled"], "the platform must not have disabled the plugin, got $state")
            assertEquals(true, state["loaded"], "the plugin must be loaded, got $state")
            assertEquals(false, state["listedAsDisabled"], "the plugin must not be recorded as disabled, got $state")
        }
    }

    /**
     * A refused paid action does not fail silently: it raises the IDE's registration dialog, naming
     * the feature that was refused. A recording action stands in for the dialog, so what is asserted
     * is that the plugin asked for it, and with what message.
     */
    @Test
    fun `a refused paid action raises the subscription dialog, naming what was refused`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Upsell")
                addFileToGroup("Second.kt", "Upsell")
                simulateUnlicensed()
                installRegisterProbe()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

                toggleActiveGroup("Upsell")
                waitFor(ofSeconds(10), errorMessage = "marking a group active raised no dialog") { registerProbeMessages().size == 1 }
                assertTrue(
                    registerProbeMessages()[0].startsWith("Marking a tab group active needs an active PrismTabs subscription."),
                    "got ${registerProbeMessages()}",
                )
                assertNull(activeGroupName(), "the refused action must not have happened")

                invokeToggleVisibility("Upsell")
                waitFor(ofSeconds(10), errorMessage = "hiding a group raised no dialog") { registerProbeMessages().size == 2 }
                assertTrue(
                    registerProbeMessages()[1].startsWith("Hiding and showing tab groups needs an active PrismTabs subscription."),
                    "got ${registerProbeMessages()}",
                )
                assertFalse(isGroupHidden("Upsell"), "the refused action must not have happened")
            } finally {
                simulateEntitled()
                removeRegisterProbe()
                closeAllEditors()
            }
        }
    }

    /**
     * Nothing broadcasts a licence change, so an open locked tool window does not unlock the instant a
     * subscription starts — only at its next natural refresh. The first test in this class covers the
     * refresh on being shown again; this one covers the other: the next change to a group.
     */
    @Test
    fun `a subscription started while the locked tool window is open shows at the next group change`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "Unlocks")
                simulateUnlicensed()
                hideTabGroupsToolWindow()
                showTabGroupsToolWindow()
                waitFor(ofSeconds(10)) { toolWindowEmptyText().contains("paid feature") }

                simulateEntitled()
                assertTrue(paidFeaturesEnabled(), "the subscription is back")
                assertTrue(
                    toolWindowEmptyText().contains("paid feature"),
                    "with no refresh yet, the window must still show what it last read, got: ${toolWindowEmptyText()}",
                )

                // A group change — marking the group active, and clearing it again — is the refresh.
                toggleActiveGroup("Unlocks")
                toggleActiveGroup("Unlocks")
                waitFor(ofSeconds(10)) { toolWindowEmptyText() == "" }
                assertTrue(remoteRobot.toolWindowGroupRows().any { it.contains("Unlocks") }, "the group list must be back")
            } finally {
                simulateEntitled()
                clearActiveGroup()
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * Both directions on one gate, in one test on purpose: the page reads its licence state **once
     * per panel** (see `TabGroupPaletteConfigurable.createPanel`), so "editable again after
     * subscribing" is a property of a *freshly built* panel and is worth nothing asserted against
     * the panel that was built while locked. Each half here builds its own.
     */
    @Test
    fun `the palette settings page is read-only when locked, and editable again once the subscription returns`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroupPalette()
                simulateUnlicensed()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

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

                simulateEntitled()
                assertTrue(paidFeaturesEnabled(), "restoring the subscription should reopen the gate")

                val unlocked = exercisePaletteConfigurable().associate {
                    val (label, value) = it.split(":", limit = 2)
                    label to value
                }
                assertEquals(
                    "true",
                    unlocked["modifiedBeforeApply"],
                    "a page opened after subscribing must take an edit again",
                )
                assertEquals(
                    "MyGrey",
                    unlocked["serviceLabelAfterApply"],
                    "an edit made after subscribing must reach TabGroupPaletteService this time",
                )
            } finally {
                simulateEntitled()
                resetTabGroupPalette()
            }
        }
    }

    /**
     * The full **subscribe → lapse → subscribe** cycle, which every test above covers only in
     * fragments: each of those lapses once and asserts that a *feature* is withheld, so none of
     * them can say what becomes of the state a subscriber built up before the lapse.
     *
     * What is asserted is the README's free/paid promise in its literal form — the free tier loses
     * features, never data — and the failure it guards against is exactly the renewal surprise a
     * paying user would report: groups quietly dropped, a hidden group forgotten so its tabs can
     * never be recovered, a saved palette reverting to the factory one. Each of those would be
     * invisible to the gate-by-gate tests, all of which would keep passing.
     *
     * Hidden state is the sharp end of it, and is set up through [IdeaFrame.invokeToggleVisibility]
     * — the gated call site — rather than [IdeaFrame.setGroupHidden], because the point is a group
     * hidden *by a subscriber exercising the paid feature*, not one placed there by a test.
     */
    @Test
    fun `groups, hidden state and a saved palette all survive a lapse and are still there after resubscribing`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                closeAllEditors()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Renewal")
                addFileToGroup("Second.kt", "Renewal")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Renewal" }

                // Everything below is what a subscriber leaves behind: a group, a paid-feature
                // hide, and a saved palette override.
                renamePalettePreset(0, "SubscriberGrey")
                assertEquals("SubscriberGrey", palettePresetLabel(0), "the subscriber's palette edit should have saved")

                invokeToggleVisibility("Renewal")
                waitFor(ofSeconds(10)) { isGroupHidden("Renewal") }
                waitFor(ofSeconds(10)) { !openTabNames().contains("Sample.kt") && !openTabNames().contains("Second.kt") }

                simulateUnlicensed()
                assertFalse(paidFeaturesEnabled(), "the simulated lapse should read as unlicensed")

                assertEquals(1, groupCount(), "a lapse must not delete the subscriber's groups")
                assertEquals("Renewal", groupNameFor("Sample.kt"), "a lapse must not drop group membership")
                assertTrue(isGroupHidden("Renewal"), "a lapse must not silently un-hide, or forget, a hidden group")
                assertEquals(
                    "SubscriberGrey",
                    palettePresetLabel(0),
                    "a saved palette must keep being used while locked — editing it is what is paid for, not keeping it",
                )

                simulateEntitled()
                assertTrue(paidFeaturesEnabled(), "restoring the subscription should reopen the gate")

                assertEquals(1, groupCount(), "resubscribing must find the same groups, not a fresh slate")
                assertEquals("SubscriberGrey", palettePresetLabel(0), "the saved palette must survive the round trip intact")
                assertTrue(isGroupHidden("Renewal"), "the group must still be hidden, and so still recoverable")

                // Not just present but usable again: the hide the subscriber left behind unwinds
                // through the same gated call that refused it while lapsed.
                invokeToggleVisibility("Renewal")
                waitFor(ofSeconds(10)) { !isGroupHidden("Renewal") }
                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt")) }
            } finally {
                simulateEntitled()
                showAllHiddenGroups()
                resetTabGroupPalette()
                closeAllEditors()
            }
        }
    }
}
