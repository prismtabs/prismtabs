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
                assertTrue(
                    remoteRobot.contextMenuItemStates().any { it == "Delete Group=false" },
                    "a selection spanning groups must grey the group actions, got ${remoteRobot.contextMenuItemStates()}",
                )

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
}
