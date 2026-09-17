package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import java.time.Duration.ofSeconds

/**
 * The third way a group empties — its last member being **deleted on disk** — driven against a live
 * IDE rather than only through `TabGroupService.clearTree`'s unit tests.
 *
 * Worth its own class because this trigger is the only one of the three that does not go through
 * [io.prismtabs.ui.TabGroupPopup] at all: it arrives on the VFS listener
 * ([io.prismtabs.listener.TabGroupVfsListener.before]) from inside the write action performing the
 * deletion, and everything that makes it work — reading the URL on the `before` side while the file
 * is still valid, and deferring the modal out of the write action with a non-modal `invokeLater` —
 * is platform behaviour that no unit test can exercise.
 *
 * Both cases create their own throwaway file rather than deleting one of the committed fixture
 * files: the sandbox opens `src/uiTest/resources/fixture-project` straight from the source tree, so
 * deleting `Sample.kt` here would delete it from the working copy for good.
 */
@ExtendWith(RemoteRobotExtension::class)
class EmptyGroupOnDiskDeleteUiTest {
    init { StepsLogger.init() }

    /**
     * Deleted the way a user actually deletes a file — selecting it in the Project tool window and
     * pressing Delete — so the platform's own confirmation dialog is in play, not just the VFS call
     * underneath it.
     *
     * That confirmation is the reason the prompt is posted with `ModalityState.nonModal()`: it has
     * to wait for the dialog driving the deletion to close rather than stack on top of it. A
     * `defaultModalityState()` here would queue the prompt behind a modality that is already gone by
     * the time it runs.
     */
    @Test
    fun `deleting a group's last member from the project view offers to delete the group`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            silenceAddToVcsConfirmation()
            resetTabGroups()
            createFixtureFile("Doomed.kt")
            openFile("Doomed.kt")
            waitFor(ofSeconds(10)) { openTabNames().contains("Doomed.kt") }
            assignFileToNewGroup("Doomed.kt", "Doomed")

            deleteFromProjectView(remoteRobot, "Doomed.kt")

            dialog("Empty Tab Group", ofSeconds(20)) {
                find<JButtonFixture>(JButtonFixture.byText("Delete Group")).click()
            }

            waitFor(ofSeconds(10)) { groupCount() == 0 }
            assertEquals(0, groupCount())
        }
    }

    /**
     * The same deletion through the VFS API the platform's delete action ends in, with the group's
     * other member left alone: the assignment for the deleted file goes, the group stays, and
     * nothing is offered for deletion.
     *
     * Asserting the *absence* of the prompt matters more here than it looks: a stray one would be
     * modal, and the next scripted read would hang rather than fail cleanly.
     */
    @Test
    fun `deleting one of several members removes its assignment and offers nothing`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            silenceAddToVcsConfirmation()
            resetTabGroups()
            createFixtureFile("Doomed.kt")
            openFile("Sample.kt")
            assignFileToNewGroup("Sample.kt", "Survivor")
            addFileToGroup("Doomed.kt", "Survivor")

            deleteFixtureFile("Doomed.kt")

            waitFor(ofSeconds(10)) { memberCount("Survivor") == 1 }
            assertEquals("Survivor", groupNameFor("Sample.kt"))
            assertEquals(1, memberCount("Survivor"), "the deleted file's assignment must be gone")
            assertFalse(hasDialog("Empty Tab Group"), "a group with a member left must not be offered for deletion")
            assertEquals(1, groupCount())
        }
    }

    /**
     * Deleting a *directory* empties both groups its two files were the last members of, and the
     * batch is offered in one dialog rather than a queue of identical ones — the case
     * [io.prismtabs.ui.promptToDeleteEmptied]'s plural wording exists for, and the reason
     * `clearTree` prefix-matches instead of taking a single URL.
     */
    @Test
    fun `deleting a directory empties several groups and offers them in one dialog`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            silenceAddToVcsConfirmation()
            resetTabGroups()
            createFixtureFile("doomed-dir/First.kt")
            createFixtureFile("doomed-dir/Second.kt")
            assignFileToNewGroup("doomed-dir/First.kt", "DirOne")
            assignFileToNewGroup("doomed-dir/Second.kt", "DirTwo")
            assertEquals(2, groupCount())

            deleteFixtureFile("doomed-dir")

            dialog("Empty Tab Groups", ofSeconds(20)) {
                find<JButtonFixture>(JButtonFixture.byText("Delete Groups")).click()
            }

            waitFor(ofSeconds(10)) { groupCount() == 0 }
            assertEquals(0, groupCount())
        }
    }
}

/**
 * Selects [fileName]'s row in the Project tool window and presses Delete, then accepts
 * whatever confirmation the platform raises — the real user gesture, rather than the VFS call
 * underneath it.
 *
 * The tool window is opened by script instead of by clicking its stripe button: this class has no
 * stake in that button, and a `java.awt.Robot` click at a stripe coordinate is the least reliable
 * step available. The tree click itself does need real input, since a tree selection is what the
 * delete action reads its target from.
 */
private fun IdeaFrame.deleteFromProjectView(remoteRobot: RemoteRobot, fileName: String) {
    runJs(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importClass(com.intellij.openapi.wm.ToolWindowManager)
        importClass(com.intellij.openapi.application.ApplicationManager)

        const frameHelper = ProjectFrameHelper.getFrameHelper(component)
        const project = frameHelper.getProject()
        const task = new Runnable({
            run: function() {
                const window = ToolWindowManager.getInstance(project).getToolWindow('Project')
                if (window != null) window.activate(null)
            }
        })
        ApplicationManager.getApplication().invokeLater(task)
        """,
        true,
    )

    // Matched on the row's rendered text, which is not always the file name: up to at least 2024.2
    // the Kotlin plugin presents a .kt file holding a single top-level declaration as that
    // declaration's name, so `Doomed.kt` renders as `Doomed`, while 2026.2 renders `Doomed.kt`.
    // Waiting for only one of the two times out against a tree showing the file perfectly well.
    val labels = setOf(fileName, fileName.substringBeforeLast('.'))
    val tree = find<JTreeFixture>(PROJECT_VIEW_TREE, ofSeconds(20))
    var label: String? = null
    waitFor(ofSeconds(20), errorMessage = "no row for '$fileName' in the project tree") {
        label = runCatching { tree.collectRows().map { it.trim() }.firstOrNull { it in labels } }.getOrNull()
        label != null
    }
    tree.clickRowWithText(label!!, true)
    Keyboard(remoteRobot).key(KeyEvent.VK_DELETE)

    // The platform's confirmation, matched by its button rather than its title so this does not
    // have to track "Delete" vs "Safe Delete" wording across IDE builds. Both spellings of the
    // button are accepted — IC-242 shows "OK" — and neither collides with the PrismTabs prompt
    // that follows, whose buttons are "Delete Group" and "Keep": that one is the caller's subject.
    val confirm = byXpath("//div[@class='JButton' and (@text='OK' or @text='Delete')]")
    waitFor(ofSeconds(20), errorMessage = "no delete confirmation appeared") {
        remoteRobot.findAll<JButtonFixture>(confirm).isNotEmpty()
    }
    remoteRobot.findAll<JButtonFixture>(confirm).last().click()
}
