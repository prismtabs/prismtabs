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
import java.awt.event.KeyEvent
import java.time.Duration.ofSeconds

/**
 * Phases 4, 6a and 7, end-to-end against a real sandbox IDE — see README "UI testing" for how to
 * launch `runIdeForUiTests` first. Covers each phase's stated acceptance criteria verbatim: hide a
 * group, see its tabs leave the strip while the group stays listed in the tool window, then show it
 * and see the tabs return in order; expand a group *row* to the files it holds, with every tab
 * closed, and open one from there; and watch the `hidden` suffix follow the tab strip as members are
 * reopened and closed one at a time.
 */
@ExtendWith(RemoteRobotExtension::class)
class TabGroupsToolWindowUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `hiding a group closes its tabs and keeps it listed, showing restores them in order`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")

                remoteRobot.createGroupViaPopup(this, "Sample.kt", colour = "Green", name = "HideMe")
                remoteRobot.openTabGroupPopupOn(this, "Second.kt")
                remoteRobot.clickPopupItem("Add to Group…")
                remoteRobot.clickPopupItem("HideMe")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "HideMe" }

                openFile("Third.kt")
                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt", "Third.kt")) }

                // The tool window lists the group with its member count before anything collapses.
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("HideMe") } }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("HideMe") && it.contains("2 files") },
                    "expected the tool window to list HideMe with 2 members, got ${remoteRobot.toolWindowGroupRows()}",
                )

                setGroupHidden("HideMe", hidden = true)

                // Hiding closes exactly the group's members; the unrelated tab stays put.
                waitFor(ofSeconds(10)) { !openTabNames().contains("Sample.kt") && !openTabNames().contains("Second.kt") }
                assertEquals(listOf("Third.kt"), openTabNames())

                // ...and the group is still listed, now marked hidden — the whole point of 4b:
                // a hidden group must remain reachable somewhere the tab strip cannot show it.
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("HideMe") && it.contains("hidden") },
                    "expected HideMe to still be listed and marked hidden, got ${remoteRobot.toolWindowGroupRows()}",
                )

                setGroupHidden("HideMe", hidden = false)

                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt")) }
                val reopened = openTabNames().filter { it == "Sample.kt" || it == "Second.kt" }
                assertEquals(listOf("Sample.kt", "Second.kt"), reopened, "expected members to reopen in their recorded order")
                assertTrue(
                    remoteRobot.toolWindowGroupRows().none { it.contains("HideMe") && it.contains("hidden") },
                    "expected HideMe to no longer read as hidden after showing it again",
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
     * The toolbar's eye, driven the way a user drives it: select the group, click Hide Group, then
     * click the very same button once it has relabelled itself Show Group.
     *
     * Every other hide/show test in this file goes through [IdeaFrame.setGroupHidden], which calls
     * `TabGroupVisibility` directly for the reason given there — and which leaves the *button*, the
     * control most users reach for, driven by nothing. What that gap hid was worse than an unclicked
     * button: in 1.4.14, Show Group could not open a single tab on any IDE from 2025.2 on (see
     * `PlatformOpenOptions`), and the whole suite stayed green, because this sandbox runs the 2024.2
     * baseline the plugin compiles against, where the call it made still resolved. This test cannot
     * catch *that* — only `verifyPlugin` and `PlatformDefaultArgumentCallsTest` can — but it is what
     * notices the day the button stops reaching the toggle at all.
     *
     * The group is re-selected between the two clicks on purpose: hiding republishes a change, the
     * panel rebuilds its model wholesale, and the toolbar is enabled by the selection that survives
     * that. Asserting the survival is `right-clicking a group row...`'s job; this test is about the
     * button, and re-selecting keeps it about the button.
     */
    @Test
    fun `the toolbar's eye hides a group and shows it again`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignToNewGroup("EyeToggle", listOf("Sample.kt", "Second.kt"))
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("EyeToggle") } }

                remoteRobot.toolWindowGroupTree().clickRowWithText("EyeToggle", false)
                remoteRobot.toolWindowToolbarButton("Hide Group").click()
                waitFor(ofSeconds(20), errorMessage = "the toolbar's eye did not close the group's tabs") {
                    openTabNames().none { it == "Sample.kt" || it == "Second.kt" }
                }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("EyeToggle") && it.contains("hidden") },
                    "expected EyeToggle to read as hidden, got ${remoteRobot.toolWindowGroupRows()}",
                )

                remoteRobot.toolWindowGroupTree().clickRowWithText("EyeToggle", false)
                remoteRobot.toolWindowToolbarButton("Show Group").click()
                waitFor(ofSeconds(20), errorMessage = "the toolbar's eye did not reopen the group's tabs") {
                    openTabNames().containsAll(listOf("Sample.kt", "Second.kt"))
                }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().none { it.contains("EyeToggle") && it.contains("hidden") },
                    "expected EyeToggle to stop reading as hidden once its tabs were back, " +
                        "got ${remoteRobot.toolWindowGroupRows()}",
                )
            } finally {
                setGroupHidden("EyeToggle", hidden = false)
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * "Hidden" follows the tab strip, not a flag — the Phase 7 amendment, driven exactly the way the
     * bug was found: hide a group, then reopen one of its files from the tree, which is what that
     * tree is *for*.
     *
     * All three of the symptoms a stored `collapsed` produced are asserted in one sequence, because
     * they were one defect: the row kept saying `hidden` beside a tab plainly on the strip; the
     * toggle kept offering Show for a group with nothing left to show; and closing the last tab by
     * hand never made the row say `hidden` at all. The last step pins the other half of the design —
     * the memory is maintained continuously, so Show reopens the file that was *last* open, not the
     * pair that was open when the group was first hidden.
     */
    @Test
    fun `hidden follows the tab strip — reopening a member un-hides a group, closing the last tab hides it`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Derived")
                addFileToGroup("Second.kt", "Derived")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Derived") && it.contains("2 files") }
                }

                setGroupHidden("Derived", hidden = true)
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Derived") && it.contains("hidden") }
                }

                // The gesture a stored flag could not see: reopen one member from the tree.
                remoteRobot.expandToolWindowGroup("Derived")
                remoteRobot.toolWindowGroupTree().doubleClickRowWithText("Sample.kt", false)
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Sample.kt") }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().none { it.contains("Derived") && it.contains("hidden") }
                }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("Derived") },
                    "the group must still be listed, just no longer hidden, got ${remoteRobot.toolWindowGroupRows()}",
                )

                // And the toggle follows the same reading: there is something to hide again.
                remoteRobot.toolWindowGroupTree().findText("Derived").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it.startsWith("Hide Group=") },
                    "a group with a tab back on the strip must offer Hide, got ${remoteRobot.contextMenuItemStates()}",
                )
                Keyboard(remoteRobot).escape()

                // Closing that last tab — no PrismTabs action involved — hides it again.
                closeAllEditors()
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Derived") && it.contains("hidden") }
                }

                // Show restores what was last open — Sample.kt alone. Second.kt was open when the
                // group was first hidden and is deliberately not resurrected: the memory tracks the
                // strip, and the user closed it.
                setGroupHidden("Derived", hidden = false)
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Sample.kt") }
                assertEquals(listOf("Sample.kt"), openTabNames())
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The bug: a group whose tabs were closed **one at a time** came back with only the last-closed
     * file, whatever order they went in. Every close used to re-record the group's still-open tabs,
     * so the memory was whittled down to the final survivor, and Show faithfully reopened just it —
     * on a row the tool window was still showing as three files.
     *
     * Closed in the order 2, 1, 3 rather than left-to-right, because that is the report's own order
     * and it also rules out a fix that merely happens to keep the first or the last tab.
     */
    @Test
    fun `a group hidden by closing its tabs one at a time comes back whole`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")
                assignFileToNewGroup("Sample.kt", "OneByOne")
                addFileToGroup("Second.kt", "OneByOne")
                addFileToGroup("Third.kt", "OneByOne")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("OneByOne") && it.contains("3 files") }
                }

                // No PrismTabs action anywhere in this sequence — just the user closing tabs.
                closeFile("Second.kt")
                waitFor(ofSeconds(10)) { openTabNames().sorted() == listOf("Sample.kt", "Third.kt") }
                closeFile("Sample.kt")
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Third.kt") }
                closeFile("Third.kt")
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("OneByOne") && it.contains("hidden") }
                }

                setGroupHidden("OneByOne", hidden = false)
                waitFor(ofSeconds(10)) { openTabNames().size == 3 }
                assertEquals(
                    listOf("Sample.kt", "Second.kt", "Third.kt"),
                    openTabNames().sorted(),
                    "Show must reopen every file the group had open, not just the last one closed",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * Hide followed by Show is an identity for a group only *some* of whose files are open. That is
     * what `TabGroupVisibility.hide` recording the exact set it is about to close provides, and it
     * is the case neither hide/show test above reaches — in both of those every member is on the
     * strip when the group goes, so recording the open set and recording the membership would be
     * indistinguishable.
     *
     * Third.kt is closed by hand first, leaving a three-file group with two tabs open. Show has to
     * bring back those two and leave the third where the user put it. Reopening all three is the
     * visible shape of a lost memory: `show` falls back to opening every member when it has nothing
     * remembered, which would resurrect a tab the user had deliberately closed.
     *
     * What it guards is that the two halves of the hidden-groups fix stay together. Against the
     * exact code before that fix — `fileClosed` upkeep *and* `hide` recording after its close loop
     * — this passes, so it is a pin on that history rather than a reproduction of it. Against
     * either half alone it fails, measured: restore `fileClosed` while `hide` records first, and
     * the close loop narrows the just-recorded list to one file before the empty-set skip freezes
     * it, so Show brings back Sample.kt on its own.
     */
    @Test
    fun `hiding a group with only some of its files open brings back exactly those`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")
                assignFileToNewGroup("Sample.kt", "PartlyOpen")
                addFileToGroup("Second.kt", "PartlyOpen")
                addFileToGroup("Third.kt", "PartlyOpen")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("PartlyOpen") && it.contains("3 files") }
                }

                closeFile("Third.kt")
                waitFor(ofSeconds(10)) { openTabNames().sorted() == listOf("Sample.kt", "Second.kt") }

                setGroupHidden("PartlyOpen", hidden = true)
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }

                setGroupHidden("PartlyOpen", hidden = false)

                waitFor(ofSeconds(10)) { openTabNames().size == 2 }
                assertEquals(
                    listOf("Sample.kt", "Second.kt"),
                    openTabNames().sorted(),
                    "Show must reopen the tabs the group had open, not every file it holds",
                )
                // The unopened file was never unassigned, only not reopened: still a three-file group.
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("PartlyOpen") && it.contains("3 files") },
                    "the member left closed must remain a member, got ${remoteRobot.toolWindowGroupRows()}",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The other half of dropping `fileClosed`: without it the memory still has to stay *bounded*.
     * It is rewritten on every open of a member with the open set as it stands at that moment, so a
     * tab the user closed and did not go back to falls out the next time they open anything in the
     * group — and Show does not resurrect it.
     *
     * Past its setup it is driven by closing and opening tabs alone, with no PrismTabs action until
     * the final Show, so what it pins is the listener rather than `TabGroupVisibility`: the group
     * ends up hidden because its last tab went, and the only thing to have written the memory by
     * then is an editor open.
     *
     * It fails in both directions, which is what makes it worth its runtime. With the old
     * `fileClosed` upkeep the one-at-a-time closes whittle the memory down and Show brings back
     * Second.kt alone; with no on-open recording at all the memory is never written, `show` falls
     * back to every member, and Third.kt comes back too.
     */
    @Test
    fun `a tab closed and not returned to drops out of the group's memory`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")
                assignFileToNewGroup("Sample.kt", "Whittle")
                addFileToGroup("Second.kt", "Whittle")
                addFileToGroup("Third.kt", "Whittle")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Whittle") && it.contains("3 files") }
                }

                // Closed and never returned to — the tab that has to be forgotten.
                closeFile("Third.kt")
                waitFor(ofSeconds(10)) { openTabNames().sorted() == listOf("Sample.kt", "Second.kt") }

                // Closed and returned to, which is the event that rewrites the memory: reopening
                // Sample.kt records the group's open set as it stands — Sample.kt and Second.kt,
                // with no Third.kt in it. Note the assignments above all happened *after* their
                // files were opened, so this is the first open of a member the listener has seen.
                closeFile("Sample.kt")
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Second.kt") }
                openFile("Sample.kt")
                waitFor(ofSeconds(10)) { openTabNames().sorted() == listOf("Sample.kt", "Second.kt") }
                // The property itself, read off the model: that open rewrote the memory, and
                // Third.kt is not in it.
                waitFor(ofSeconds(10)) { rememberedTabUrlsOf("Whittle").size == 2 }
                val remembered = rememberedTabUrlsOf("Whittle")
                assertEquals(
                    listOf("Sample.kt", "Second.kt"),
                    remembered.map { it.substringAfterLast('/') }.sorted(),
                    "opening a member must re-record the group's open set, dropping the tab left " +
                        "closed — got $remembered",
                )

                // Hidden by hand, so nothing but that open has written the memory.
                closeFile("Sample.kt")
                closeFile("Second.kt")
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Whittle") && it.contains("hidden") }
                }

                setGroupHidden("Whittle", hidden = false)

                waitFor(ofSeconds(10)) { openTabNames().size == 2 }
                assertEquals(
                    listOf("Sample.kt", "Second.kt"),
                    openTabNames().sorted(),
                    "Show must reopen the group's last open arrangement — both tabs that were open, " +
                        "and not the one closed and left closed",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The context menu must act on the row the user right-clicked, whatever was selected before —
     * the regression guarded here is a menu that comes up entirely greyed out, which is what
     * happens when a right-click doesn't move the tree's selection (Swing moves it for the left
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
                // Both members open, and stated rather than inherited from whatever ran before:
                // the Hide/Show assertion below turns on it, since a group with no open tab reads
                // as hidden and offers Show (see TabGroupVisibility).
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "RightClickA")
                assignFileToNewGroup("Second.kt", "RightClickB")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 2 }

                // Nothing selected, so this is the plain form of the bug — and *made* so rather
                // than assumed: the tree outlives each test and `refresh` deliberately falls a
                // stale selection onto a surviving row, so whether this precondition holds on its
                // own depends on which class ran before this one (see
                // [IdeaFrame.clearToolWindowSelection]).
                clearToolWindowSelection()
                val tree = remoteRobot.toolWindowGroupTree()
                assertTrue(tree.collectSelectedPaths().isEmpty(), "precondition: the tree starts with no selection")
                tree.findText("RightClickA").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().all { it.endsWith("=true") },
                    "a right-click on a row must enable that row's actions, got ${remoteRobot.contextMenuItemStates()}",
                )
                assertTrue(
                    remoteRobot.selectedToolWindowRows().any { it.contains("RightClickA") },
                    "the right-clicked row must be the selected one, got ${remoteRobot.selectedToolWindowRows()}",
                )
                // The wording the menu offers, asserted where it is actually rendered: the toggle
                // reads Hide/Show, never Collapse/Expand, and a group with tabs open offers Hide.
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it.startsWith("Hide Group=") },
                    "a group with tabs open must offer Hide Group, got ${remoteRobot.contextMenuItemStates()}",
                )

                // Delete it from that very menu: the selected row is the one that disappears.
                remoteRobot.actionMenuItem("Delete Group").click()
                dialog("Delete Tab Group") {
                    find<JButtonFixture>(JButtonFixture.byText("Delete")).click()
                }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 1 }
                assertTrue(
                    remoteRobot.selectedToolWindowRows().any { it.contains("RightClickB") },
                    "deleting the selected group must leave the surviving row selected, not an empty selection",
                )

                // ...and the very next right-click, with no left-click to revive the panel first.
                remoteRobot.toolWindowGroupTree().findText("RightClickB").rightClick()
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

    /**
     * The gap this closes: marking a group active used to be reachable only from a tab's
     * right-click menu, two levels down (Tab Group… → Manage Groups… → the group), even though the
     * tool window is where a subscriber otherwise manages groups. Driven the way a user would —
     * right-click the row, click the toggle — and asserted on the behaviour it buys (a newly opened
     * file joins the group), not just on the service field, so a toggle wired to the wrong row
     * would fail here.
     */
    @Test
    fun `the tool window's Active Group toggle marks the right-clicked group, and clears it again`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "StickyA")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 1 }

                remoteRobot.toolWindowGroupTree().findText("StickyA").rightClick()
                remoteRobot.actionMenuItem("Active Group").click()
                waitFor(ofSeconds(10)) { activeGroupName() == "StickyA" }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("StickyA") && it.contains("active") },
                    "the marked row must say so, got ${remoteRobot.toolWindowGroupRows()}",
                )

                // What "active" is for: the next file opened joins the group without being assigned.
                openFile("Second.kt")
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "StickyA" }

                // The same item clears it — the toggle reads its state from the selected row.
                remoteRobot.toolWindowGroupTree().findText("StickyA").rightClick()
                remoteRobot.actionMenuItem("Active Group").click()
                waitFor(ofSeconds(10)) { activeGroupName() == null }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().none { it.contains("active") },
                    "clearing must drop the row's suffix, got ${remoteRobot.toolWindowGroupRows()}",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The reported defect: with a group marked active, opening a file from the Project view joins
     * it to that group — the tab takes the colour and the service agrees — while the tool window's
     * tree goes on showing the group's old membership and count, catching up only on the next
     * group event, which is why deactivating or hiding the group appears to "fix" it.
     *
     * Two subscribers to one event decide it. The panel refreshes on `fileOpened` as well as on
     * PrismTabs' own change topic, and the auto-join happens on that very same `fileOpened`, so
     * whether the tree is rebuilt before or after the file joins comes down to the order the
     * message bus calls them in — and that order is the platform's to choose, since
     * `TabGroupFileListener` is a lazily-subscribed `projectListeners` entry. `TabGroupFileListener`
     * now publishes the change itself, as every hand-driven assignment path does, so the refresh
     * lands after the mutation either way.
     *
     * Measured on the sandbox IDE (2024.2, plugin loaded at startup), the platform happens to call
     * the auto-join first, so this test passes against the unfixed plugin there too — it pins the
     * behaviour rather than reproducing the ordering. What it does cover outright is the scenario
     * itself, which had none: the existing active-group tests assert the *service*, never the tree.
     *
     * Driven through the Project view because that is where it was reported, and asserted with
     * nothing else touched in between: every step after the open is a pure read of the tree, since
     * selecting a row, hiding the window or toggling the group would each republish a change and
     * hand the tree the refresh whose absence is the defect.
     */
    @Test
    fun `a file that auto-joins the active group shows up in the tree at once`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                // Opened *first*, before any file — the state a user who keeps this window
                // open is in, and the one that puts the panel's own `fileOpened` refresh closest
                // to racing the auto-join.
                showTabGroupsToolWindow()
                openFile("Sample.kt")
                assignFileToNewGroup("Sample.kt", "AutoJoined")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("AutoJoined") && it.contains("1 file") }
                }

                // Marked active through the same choke point the tool window's "Active Group"
                // toggle and the tab popup's item both call. That the *menu* reaches it is
                // already covered above; going through the menu here would leave a popup on
                // screen swallowing the expand key pressed a moment later.
                toggleActiveGroup("AutoJoined")
                waitFor(ofSeconds(10)) { activeGroupName() == "AutoJoined" }

                // Expanded *before* the open, so the members below are already on screen: an
                // expansion done afterwards would be a fresh click on the tree and could mask a
                // missing refresh.
                remoteRobot.expandToolWindowGroup("AutoJoined")

                openFromProjectView("Second.kt")

                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "AutoJoined" }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("AutoJoined") && it.contains("2 files") }
                }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.trim().startsWith("Second.kt") },
                    "the auto-joined file must appear under its group without any further event, " +
                        "got ${remoteRobot.toolWindowGroupRows()}",
                )
            } finally {
                // The active group outlives the test otherwise, and would silently swallow every
                // file the next class opens. resetTabGroups clears it, but only for tests that
                // reach it — a failure here must not poison the rest of the suite.
                clearActiveGroup()
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The mechanism behind the test above, and the half of it that can actually fail on the defect.
     *
     * `TabGroupFileListener` and `TabGroupsPanel` both react to `fileOpened`, so whether the tree is
     * rebuilt after the auto-join or a moment before it is the message bus's choice rather than the
     * plugin's — on this sandbox it calls the auto-join first, which is why the tree assertion above
     * passes even against the unfixed plugin and can only pin the behaviour. What the fix adds is
     * the announcement itself: `autoJoinActiveGroup` publishes on `TabGroupsChangedListener.TOPIC`,
     * exactly as every hand-driven assignment path does, putting a refresh after the mutation in
     * either order. Counting that publication holds whatever the platform does with its subscriber
     * list — delete the `TabGroupNotifier.notifyChanged` line and this test fails on any machine.
     *
     * The tool window is deliberately left closed: the probe counts publications on the topic, and
     * keeping the panel out of it leaves the count attributable to the auto-join alone.
     */
    @Test
    fun `auto-joining the active group announces the change on the topic`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                assignFileToNewGroup("Sample.kt", "Announced")
                toggleActiveGroup("Announced")
                waitFor(ofSeconds(10)) { activeGroupName() == "Announced" }

                // Both preconditions matter, and `resetTabGroups` closing every editor is what
                // gives us them: an already-open file fires no `fileOpened` at all, and an
                // already-assigned one returns from the auto-join before the announcement.
                val openBefore = openTabNames()
                assertTrue("Second.kt" !in openBefore, "Second.kt must not already be open, got $openBefore")
                assertNull(groupNameFor("Second.kt"), "Second.kt must start out unassigned")

                installTopicProbe()
                openFile("Second.kt")
                // Waited for rather than assumed: the open lands on a later event-queue turn, and
                // the publication being counted happens inside the listener that this wait is
                // observing the other half of.
                waitFor(ofSeconds(10)) { groupNameFor("Second.kt") == "Announced" }

                // Read once: the assertion message is built whether or not it fails, and a second
                // call would be a second reading of a live counter rather than the one asserted on.
                val publications = topicProbeCount()
                assertTrue(
                    publications >= 1,
                    "auto-join must publish TabGroupsChangedListener.TOPIC, so the tool window's tree " +
                        "refreshes after the membership change whichever order the message bus calls " +
                        "its subscribers in — got $publications publications",
                )
            } finally {
                removeTopicProbe()
                // No tool window to put away, but the active group would otherwise swallow every
                // file the next test opens. See the test above.
                clearActiveGroup()
            }
        }
    }

    /**
     * Phase 6a's acceptance criterion, driven as the plan states it: a group's members are files,
     * and the tool window has to say which — *especially* once none of them is open, which is the
     * case the tab strip cannot answer at all.
     *
     * Four things at once, because they only make sense together: the group row expands to its
     * members; the members are still listed with every tab closed; double-clicking a member opens
     * it; and double-clicking the *group* row still hides the group rather than opening anything —
     * the one binding Phase 6a had to keep from leaking onto the new child rows. The last assertion
     * is the expansion-state guard: hiding republishes a change, `refresh()` swaps the model
     * wholesale, and an expanded group that collapsed itself under the user at that moment was the
     * failure mode the plan called out by name.
     */
    @Test
    fun `a group row expands to its files, which stay listed with every tab closed`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Members")
                addFileToGroup("Second.kt", "Members")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Members") && it.contains("2 files") }
                }

                // The whole point of the phase: close every tab, so the strip can no longer answer
                // "what is in this group?", and ask the tool window instead.
                closeAllEditors()
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }

                val members = remoteRobot.toolWindowMemberRows("Members")
                assertEquals(
                    listOf("Sample.kt", "Second.kt"),
                    members.map { it.trim().substringBefore("  ") },
                    "expected the group to expand to its two members by name, got $members",
                )

                // The toolbar and the context menu act on "the selected group, or the selected
                // file's parent group": without that second half, selecting a child row — the
                // ordinary way to use this tree — would grey out every action in the window.
                remoteRobot.toolWindowGroupTree().findText("Sample.kt").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().all { it.endsWith("=true") },
                    "a right-click on a member row must enable its group's actions, got ${remoteRobot.contextMenuItemStates()}",
                )
                // Show, not Hide: every tab is closed, so the group *is* hidden and the toggle
                // says the only useful thing it could. That the wording follows the strip rather
                // than a stored flag is the point — see TabGroupVisibility.
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it.startsWith("Show Group=") },
                    "and they must be the parent group's, got ${remoteRobot.contextMenuItemStates()}",
                )
                Keyboard(remoteRobot).escape()

                // Double-click on a member row opens it — the free popup's "Files…" behaviour, here.
                remoteRobot.toolWindowGroupTree().doubleClickRowWithText("Second.kt", false)
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Second.kt") }

                // ...while double-click on the *group* row still means Hide Group.
                remoteRobot.toolWindowGroupTree().doubleClickRowWithText("Members", false)
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("Members") && it.contains("hidden") },
                    "double-clicking a group row must hide it, got ${remoteRobot.toolWindowGroupRows()}",
                )
                assertTrue(
                    remoteRobot.toolWindowGroupRows().any { it.contains("Sample.kt") },
                    "hiding must not collapse the tree row the user had open, got ${remoteRobot.toolWindowGroupRows()}",
                )
            } finally {
                // Hidden groups keep their tabs closed, so unhide before leaving: the next test
                // starts from resetTabGroups, but a stray hidden group would still be holding
                // this one's members shut if that ever changed.
                setGroupHidden("Members", hidden = false)
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * Phase 6c: dropping a file from its group without opening it first. The tool window is the
     * only surface that lists a member whose tab is closed, so it has to be the one that can
     * unassign it — before this, the operation lived on the editor tab's own right-click menu and
     * a closed member had no tab to right-click.
     *
     * Driven from the state that makes the point: every tab closed, so nothing here could have gone
     * through the tab popup. Both halves of the item's scoping are asserted from the same menu —
     * it is offered, enabled, on a file row, and absent on a group row, where there is no one file
     * for it to act on and "Delete Group" is the destructive neighbour it must not be confused
     * with.
     */
    @Test
    fun `a member row offers Remove from Group, and a group row does not`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Unassign")
                addFileToGroup("Second.kt", "Unassign")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Unassign") && it.contains("2 files") }
                }

                // The case the tab strip cannot serve: no tab is open for either member.
                closeAllEditors()
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }

                remoteRobot.expandToolWindowGroup("Unassign")
                remoteRobot.toolWindowGroupTree().findText("Sample.kt").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it == "Remove from Group=true" },
                    "a member row must offer Remove from Group, enabled, got ${remoteRobot.contextMenuItemStates()}",
                )

                remoteRobot.actionMenuItem("Remove from Group").click()

                // The assignment is gone, the group survives with its other member, and the tree
                // says so — a removal the group list did not notice would be the real regression.
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Unassign") && it.contains("1 file") }
                }
                assertEquals("Unassign", groupNameFor("Second.kt"), "the other member must be untouched")
                assertEquals(
                    listOf("Second.kt"),
                    remoteRobot.toolWindowMemberRows("Unassign").map { it.trim().substringBefore("  ") },
                    "the removed file must be gone from the tree, got ${remoteRobot.toolWindowMemberRows("Unassign")}",
                )

                // ...and the item is a file's, not a group's: no group row offers it.
                remoteRobot.toolWindowGroupTree().findText("Unassign").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().none { it.startsWith("Remove from Group=") },
                    "a group row must not offer Remove from Group, got ${remoteRobot.contextMenuItemStates()}",
                )
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it.startsWith("Delete Group=") },
                    "...while still offering the group's own actions, got ${remoteRobot.contextMenuItemStates()}",
                )
                Keyboard(remoteRobot).escape()
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * Multi-select removal, driven with the gesture it exists for: toggle-click two of a group's
     * three files, then remove both in one go. The modifier held is the platform's own — Ctrl on
     * Linux and Windows, ⌘ on macOS — see [toggleSelectionKey]. Three things are asserted that
     * single-selection could not reach — the item states the count, the right-click *inside* an existing selection
     * keeps that selection whole (without which the menu would discard the very rows it acts on),
     * and the group's own actions stay live because every selected row belongs to one group.
     */
    @Test
    fun `multi-selected files are removed together, and the item states the count`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")
                assignFileToNewGroup("Sample.kt", "Bulk")
                addFileToGroup("Second.kt", "Bulk")
                addFileToGroup("Third.kt", "Bulk")
                closeAllEditors()
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Bulk") && it.contains("3 files") }
                }

                remoteRobot.expandToolWindowGroup("Bulk")
                val tree = remoteRobot.toolWindowGroupTree()
                tree.clickRowWithText("Sample.kt", false)
                Keyboard(remoteRobot).pressing(remoteRobot.toggleSelectionKey()) {
                    tree.clickRowWithText("Second.kt", false)
                }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowSelectionCount() == 2 }

                // Right-click one of the two selected rows: the selection must survive it.
                tree.findText("Second.kt").rightClick()
                assertEquals(
                    2,
                    remoteRobot.toolWindowSelectionCount(),
                    "right-clicking inside a selection must keep it, got ${remoteRobot.selectedToolWindowRows()}",
                )
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it == "Remove 2 Files from Group=true" },
                    "the item must state the count, got ${remoteRobot.contextMenuItemStates()}",
                )
                // Every selected row is in one group, so the group's own actions are unambiguous.
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it == "Delete Group=true" },
                    "a selection within one group must leave its group actions live, got ${remoteRobot.contextMenuItemStates()}",
                )

                remoteRobot.actionMenuItem("Remove 2 Files from Group").click()

                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null && groupNameFor("Second.kt") == null }
                assertEquals("Bulk", groupNameFor("Third.kt"), "the unselected member must stay put")
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().any { it.contains("Bulk") && it.contains("1 file") }
                }
                assertEquals(
                    listOf("Third.kt"),
                    remoteRobot.toolWindowMemberRows("Bulk").map { it.trim().substringBefore("  ") },
                    "only the unselected member may be left, got ${remoteRobot.toolWindowMemberRows("Bulk")}",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The other half of multi-selection: rows from *two* groups. "Remove from Group" still applies —
     * unassigning is well defined however the selection is spread, and says so in its label — while
     * the group actions must go grey, since "rename this group" has no answer for a selection that
     * spans two. Emptying both in one gesture is also the batch case of the empty-group prompt:
     * one dialog naming both groups, not one dialog each.
     */
    @Test
    fun `a selection spanning two groups still removes, greys the group actions, and asks once`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "SpanA")
                assignFileToNewGroup("Second.kt", "SpanB")
                closeAllEditors()
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 2 }

                remoteRobot.expandToolWindowGroup("SpanA")
                remoteRobot.expandToolWindowGroup("SpanB")
                val tree = remoteRobot.toolWindowGroupTree()
                tree.clickRowWithText("Sample.kt", false)
                Keyboard(remoteRobot).pressing(remoteRobot.toggleSelectionKey()) {
                    tree.clickRowWithText("Second.kt", false)
                }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowSelectionCount() == 2 }

                tree.findText("Second.kt").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it == "Remove 2 Files from Their Groups=true" },
                    "the label must not claim one group, got ${remoteRobot.contextMenuItemStates()}",
                )
                val states = remoteRobot.contextMenuItemStates()
                // "Hide Group", not "Show Group", although both groups' tabs are closed: with no single
                // group selected there is no group to read as hidden, so the eye keeps its default label.
                listOf("Active Group", "Hide Group", "Rename…", "Recolour…", "Delete Group").forEach { action ->
                    assertTrue(
                        states.contains("$action=false"),
                        "a selection spanning groups must grey every group action, $action included, got $states",
                    )
                }

                remoteRobot.actionMenuItem("Remove 2 Files from Their Groups").click()

                // Both groups are left empty by the one gesture, so the prompt asks about both at
                // once — the plural wording is the assertion that it is a single batched dialog.
                dialog("Empty Tab Groups") {
                    find<JButtonFixture>(JButtonFixture.byText("Keep")).click()
                }
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null && groupNameFor("Second.kt") == null }
                waitFor(ofSeconds(10)) {
                    remoteRobot.toolWindowGroupRows().count { it.contains("0 files") } == 2
                }
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * What each kind of row is drawn with, read off the renderer: a group row carries its colour dot,
     * name and greyed count; a member row carries its file type's own icon, its name — bold while its
     * tab is open, regular once closed — and its project-relative directory in grey.
     */
    @Test
    fun `rows carry the group's dot, each file's type icon, a greyed directory, and bold for an open tab`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                silenceAddToVcsConfirmation()
                resetTabGroups()
                createFixtureFile("tree-rows/notes.txt")
                openFile("Sample.kt")
                assignFileToNewGroup("Sample.kt", "Rendered", lightRgb = 0xAECBFA, darkRgb = 0x2E5C9C)
                addFileToGroup("tree-rows/notes.txt", "Rendered")
                remoteRobot.expandToolWindowGroup("Rendered")

                val dot = themed(0xAECBFA, 0x2E5C9C).hex()
                val expected = listOf(
                    "swatch:$dot|Rendered{plain}¦  2 files{grey}",
                    "filetype:Kotlin|Sample.kt{bold}",
                    "filetype:PLAIN_TEXT|notes.txt{plain}¦  tree-rows{grey}",
                )
                waitFor(ofSeconds(10), errorMessage = "got ${toolWindowRowRendering()}") { toolWindowRowRendering() == expected }

                closeFile("Sample.kt")
                waitFor(ofSeconds(10), errorMessage = "a closed member must render regular, got ${toolWindowRowRendering()}") {
                    toolWindowRowRendering().getOrNull(1) == "filetype:Kotlin|Sample.kt{plain}"
                }
            } finally {
                deleteFixtureFile("tree-rows")
                hideTabGroupsToolWindow()
            }
        }
    }

    /** Enter on a group row is the tree's own expand key, never Hide Group: the tabs stay where they are. */
    @Test
    fun `Enter expands and collapses a group row without hiding it`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Keyed")
                addFileToGroup("Second.kt", "Keyed")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("Keyed") && it.contains("2 files") } }

                remoteRobot.toolWindowGroupTree().clickRowWithText("Keyed", false)
                assertFalse(isToolWindowGroupExpanded("Keyed"), "precondition: a new group's row starts collapsed")

                Keyboard(remoteRobot).enter()
                waitFor(ofSeconds(10)) { isToolWindowGroupExpanded("Keyed") }
                assertEquals(listOf("Sample.kt", "Second.kt"), openTabNames().sorted(), "Enter must not close the group's tabs")
                assertFalse(isGroupHidden("Keyed"))

                Keyboard(remoteRobot).enter()
                waitFor(ofSeconds(10)) { !isToolWindowGroupExpanded("Keyed") }
                assertEquals(listOf("Sample.kt", "Second.kt"), openTabNames().sorted(), "nor may a second Enter")
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    @Test
    fun `Hide Group and Show Group in the context menu close the group's tabs and reopen them in order`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                openFile("Sample.kt")
                openFile("Second.kt")
                openFile("Third.kt")
                assignFileToNewGroup("Sample.kt", "Menu")
                addFileToGroup("Second.kt", "Menu")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("Menu") && it.contains("2 files") } }

                remoteRobot.toolWindowGroupTree().findText("Menu").rightClick()
                remoteRobot.actionMenuItem("Hide Group").click()
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Third.kt") }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.contains("Menu") && it.contains("hidden") } }

                remoteRobot.toolWindowGroupTree().findText("Menu").rightClick()
                remoteRobot.actionMenuItem("Show Group").click()
                waitFor(ofSeconds(10)) { openTabNames().containsAll(listOf("Sample.kt", "Second.kt")) }
                assertEquals(
                    listOf("Sample.kt", "Second.kt"),
                    openTabNames().filter { it != "Third.kt" },
                    "the group's tabs must come back in the order they were open",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /** The toggle from both of its surfaces, moving the one active mark from group to group. */
    @Test
    fun `the Active Group toggle in the context menu and the toolbar moves the active mark between groups`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "ToggleA")
                assignFileToNewGroup("Second.kt", "ToggleB")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 2 }

                remoteRobot.toolWindowGroupTree().findText("ToggleA").rightClick()
                remoteRobot.actionMenuItem("Active Group").click()
                waitFor(ofSeconds(10)) { activeGroupName() == "ToggleA" }
                // A toggle item keeps its context menu open once clicked (the platform's own
                // behaviour for checkable items, measured here), and an open menu swallows the next
                // click on the tree — so it is closed first, as a user would.
                Keyboard(remoteRobot).escape()
                waitFor(ofSeconds(10)) { remoteRobot.contextMenuItemStatesNow().isEmpty() }

                remoteRobot.toolWindowGroupTree().clickRowWithText("ToggleB", false)
                remoteRobot.toolWindowToolbarButton("Active Group").click()
                waitFor(ofSeconds(10)) { activeGroupName() == "ToggleB" }

                waitFor(ofSeconds(10), errorMessage = "got ${remoteRobot.toolWindowGroupRows()}") {
                    val rows = remoteRobot.toolWindowGroupRows()
                    rows.any { it.startsWith("ToggleB") && it.contains("active") } &&
                        rows.none { it.startsWith("ToggleA") && it.contains("active") }
                }
            } finally {
                clearActiveGroup()
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * Rename, Recolour and Delete offered live on both surfaces, and each one doing its job from one of
     * them: Rename from the context menu, Recolour and Delete from the toolbar. Both surfaces wrap the
     * very same action instances, so what one does the other does.
     */
    @Test
    fun `Rename, Recolour and Delete work from the tool window's context menu and toolbar`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                openFile("Sample.kt")
                assignFileToNewGroup("Sample.kt", "Surfaces")
                assignFileToNewGroup("Second.kt", "Bystander")
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().size == 2 }

                remoteRobot.toolWindowGroupTree().findText("Surfaces").rightClick()
                val states = remoteRobot.contextMenuItemStates()
                listOf("Rename…=true", "Recolour…=true", "Delete Group=true").forEach {
                    assertTrue(states.contains(it), "expected $it in the context menu, got $states")
                }
                remoteRobot.actionMenuItem("Rename…").click()
                dialog("Rename Tab Group") {
                    find<JTextFieldFixture>(JTextFieldFixture.byType()).text = "Renamed"
                    find<JButtonFixture>(JButtonFixture.byText("OK")).click()
                }
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == "Renamed" }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.startsWith("Renamed") } }

                remoteRobot.toolWindowGroupTree().clickRowWithText("Renamed", false)
                remoteRobot.toolWindowToolbarButton("Recolour…").click()
                remoteRobot.clickPopupItem("Cyan")
                val cyan = TabGroupPalette.PRESETS.first { it.id == "cyan" }
                waitFor(ofSeconds(10)) { groupLightRgbFor("Sample.kt") == cyan.light.rgb and 0xFFFFFF }
                waitFor(ofSeconds(10), errorMessage = "the member's tab was not repainted") { tabRgb("Sample.kt") == themedPreset("cyan") }

                remoteRobot.toolWindowGroupTree().clickRowWithText("Renamed", false)
                remoteRobot.toolWindowToolbarButton("Delete Group").click()
                dialog("Delete Tab Group") { find<JButtonFixture>(JButtonFixture.byText("Delete")).click() }
                waitFor(ofSeconds(10)) { groupNameFor("Sample.kt") == null }
                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().none { it.startsWith("Renamed") } }
                assertEquals("Bystander", groupNameFor("Second.kt"), "only the selected group may go")
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The platform's own file actions, working off a member row because the panel publishes the row's
     * file: F4 opens it, Copy Path copies its absolute path, Select in Project View selects it there.
     * F4 is pressed for real; the other two run against the tree's own data context, which is what
     * their shortcuts hand them in whatever keymap the IDE uses.
     */
    @Test
    fun `a member row feeds Jump to Source, Copy Path and Select in Project View`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "Actions")
                closeAllEditors()
                remoteRobot.expandToolWindowGroup("Actions")

                remoteRobot.toolWindowGroupTree().clickRowWithText("Sample.kt", false)
                Keyboard(remoteRobot).key(KeyEvent.VK_F4)
                waitFor(ofSeconds(10), errorMessage = "F4 on the member row did not open it") { openTabNames() == listOf("Sample.kt") }

                remoteRobot.toolWindowGroupTree().clickRowWithText("Sample.kt", false)
                remoteRobot.setClipboardText("nothing copied yet")
                invokeActionOnToolWindowTree("CopyPaths")
                waitFor(ofSeconds(10), errorMessage = "Copy Path copied ${remoteRobot.clipboardText()}") {
                    remoteRobot.clipboardText().endsWith("/fixture-project/Sample.kt")
                }

                invokeActionOnToolWindowTree("SelectInProjectView")
                waitFor(ofSeconds(20), errorMessage = "the Project view selected ${projectViewSelection()}") {
                    projectViewSelection() == listOf("Sample.kt")
                }
            } finally {
                hideProjectView()
                hideTabGroupsToolWindow()
            }
        }
    }

    /**
     * The model is rebuilt wholesale on every change, so the two pieces of view state Swing throws
     * away with it — which groups were expanded, and which row was selected — have to be put back.
     * The change comes from elsewhere, a recolour in a tab's popup, as it would for a user.
     */
    @Test
    fun `a group recoloured from a tab's popup stays expanded and selected in the tree`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                openFile("Sample.kt")
                openFile("Second.kt")
                assignFileToNewGroup("Sample.kt", "Kept")
                addFileToGroup("Second.kt", "Kept")
                assignFileToNewGroup("Third.kt", "Unrelated")
                remoteRobot.expandToolWindowGroup("Kept")
                remoteRobot.toolWindowGroupTree().clickRowWithText("Kept", false)
                assertTrue(isToolWindowGroupExpanded("Kept"))

                remoteRobot.openTabGroupPopupOn(this, "Sample.kt")
                remoteRobot.clickPopupItem("Manage Groups…")
                remoteRobot.popupSubstepItems("Kept", anchor = "Recolour…")
                remoteRobot.popupSubstepItems("Recolour…", anchor = "Recolour…")
                waitFor(ofSeconds(10)) { remoteRobot.popupRowSwatches().size == TabGroupPalette.PRESETS.size }
                remoteRobot.clickPopupItem("Red")
                val red = TabGroupPalette.PRESETS.first { it.id == "red" }
                waitFor(ofSeconds(10)) { groupLightRgbFor("Sample.kt") == red.light.rgb and 0xFFFFFF }

                assertTrue(isToolWindowGroupExpanded("Kept"), "the recolour must not collapse the group")
                assertTrue(
                    remoteRobot.selectedToolWindowRows().any { it.startsWith("Kept") },
                    "the recolour must not drop the selection, got ${remoteRobot.selectedToolWindowRows()}",
                )
                assertTrue(
                    toolWindowRowRendering().first().startsWith("swatch:${themedPreset("red").hex()}|Kept"),
                    "and the row must show the new colour, got ${toolWindowRowRendering()}",
                )
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }

    /** "Keep" on the empty-group prompt, seen from the tool window: a 0-file row that can still be deleted later. */
    @Test
    fun `a group kept after its last file is removed lists 0 files and still offers Delete Group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                assignFileToNewGroup("Sample.kt", "Emptied")
                remoteRobot.expandToolWindowGroup("Emptied")

                remoteRobot.toolWindowGroupTree().findText("Sample.kt").rightClick()
                remoteRobot.actionMenuItem("Remove from Group").click()
                dialog("Empty Tab Group") { find<JButtonFixture>(JButtonFixture.byText("Keep")).click() }

                waitFor(ofSeconds(10)) { remoteRobot.toolWindowGroupRows().any { it.startsWith("Emptied") && it.contains("0 files") } }
                remoteRobot.toolWindowGroupTree().findText("Emptied").rightClick()
                assertTrue(
                    remoteRobot.contextMenuItemStates().contains("Delete Group=true"),
                    "a kept empty group must still be deletable, got ${remoteRobot.contextMenuItemStates()}",
                )
                Keyboard(remoteRobot).escape()
            } finally {
                hideTabGroupsToolWindow()
            }
        }
    }
}
