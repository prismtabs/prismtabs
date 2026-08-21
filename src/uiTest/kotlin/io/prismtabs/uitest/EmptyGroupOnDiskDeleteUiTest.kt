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
            resetTabGroups()
            createFixtureFile("Doomed.kt")
            openFile("Doomed.kt")
            waitFor(ofSeconds(10)) { openTabNames().contains("Doomed.kt") }
            assignFileToNewGroup("Doomed.kt", "Doomed")

            deleteFromProjectView(remoteRobot, "Doomed")

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
 * Creates [path] under the fixture project on disk, parent directories included, replacing anything
 * an interrupted earlier run left behind.
 *
 * Runs in a real write action for the same reason the deletion does: VFS mutation outside one
 * throws, and the point of this class is to go through the platform rather than around it.
 *
 * Dispatched off the EDT with an explicit `ModalityState.nonModal()`, exactly as
 * [IdeaFrame.forceSaveSettings] documents at length: a `remote-robot` script runs under a modality
 * that is not write-safe, and `ApplicationImpl.invokeAndWait` only establishes a write-safe context
 * on its off-EDT branch. Without it the VFS write still lands, but the platform logs a
 * `TransactionGuardImpl` "Write-unsafe context!" SEVERE for every call — measured here, and exactly
 * the noise a verification pass then has to explain away in `idea.log`.
 */
private fun IdeaFrame.createFixtureFile(path: String) {
    runJs(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.openapi.vfs)
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.openapi.application.ModalityState)

        var frameHelper = ProjectFrameHelper.getFrameHelper(component)
        var project = frameHelper.getProject()
        var app = ApplicationManager.getApplication()
        var task = new Runnable({
            run: function() {
                app.runWriteAction(new Runnable({
                    run: function() {
                        var root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath())
                        var segments = '$path'.split('/')
                        var dir = root
                        for (var i = 0; i < segments.length - 1; i++) {
                            var existingDir = dir.findChild(segments[i])
                            dir = existingDir != null ? existingDir : dir.createChildDirectory(null, segments[i])
                        }
                        var name = segments[segments.length - 1]
                        var stale = dir.findChild(name)
                        if (stale != null) stale.delete(null)
                        var created = dir.createChildData(null, name)
                        VfsUtil.saveText(created, 'object ' + name.replace('.kt', '') + '\n')
                    }
                }))
            }
        })
        app.invokeAndWait(task, ModalityState.nonModal())
        """,
        false,
    )
}

/**
 * Deletes [path] under the fixture project through the VFS, inside a write action — the same call
 * the platform's own delete action ends in, which is what
 * [io.prismtabs.listener.TabGroupVfsListener.before] hangs off.
 */
private fun IdeaFrame.deleteFixtureFile(path: String) {
    runJs(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.openapi.vfs)
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.openapi.application.ModalityState)

        var frameHelper = ProjectFrameHelper.getFrameHelper(component)
        var project = frameHelper.getProject()
        var app = ApplicationManager.getApplication()
        var task = new Runnable({
            run: function() {
                app.runWriteAction(new Runnable({
                    run: function() {
                        var target = LocalFileSystem.getInstance()
                            .refreshAndFindFileByPath(project.getBasePath() + '/$path')
                        if (target != null) target.delete(null)
                    }
                }))
            }
        })
        app.invokeAndWait(task, ModalityState.nonModal())
        """,
        false,
    )
}

/**
 * Selects the row labelled [label] in the Project tool window and presses Delete, then accepts
 * whatever confirmation the platform raises — the real user gesture, rather than the VFS call
 * underneath it.
 *
 * The tool window is opened by script instead of by clicking its stripe button: this class has no
 * stake in that button, and a `java.awt.Robot` click at a stripe coordinate is the least reliable
 * step available. The tree click itself does need real input, since a tree selection is what the
 * delete action reads its target from.
 */
private fun IdeaFrame.deleteFromProjectView(remoteRobot: RemoteRobot, label: String) {
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

    dismissAddToGitPrompt(remoteRobot)

    // Matched on the row's rendered text, which is **not** the file name: the Kotlin plugin
    // presents a .kt file holding a single top-level declaration as that declaration's name, so
    // `Doomed.kt` renders as `Doomed`. Waiting for the file name here times out against a tree that
    // is showing the file perfectly well — hence [label] being the caller's to supply.
    val tree = find<JTreeFixture>(byXpath("//div[@class='ProjectViewTree']"), ofSeconds(20))
    waitFor(ofSeconds(20), errorMessage = "no '$label' row in the project tree") {
        runCatching { tree.collectRows().any { it.trim() == label } }.getOrDefault(false)
    }
    tree.clickRowWithText(label, true)
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


/**
 * How many files are assigned to the group named [groupName], read straight off the service.
 *
 * [IdeaFrame.groupNameFor] cannot answer this once the file is gone: it resolves the path through
 * `LocalFileSystem.findFileByPath` to get a URL, and a deleted file resolves to null. Membership is
 * a URL -> group id map that outlives the `VirtualFile`, so counting members is the assertion that
 * still works after a deletion.
 */
private fun IdeaFrame.memberCount(groupName: String): Int = callJs(
    """
    importPackage(com.intellij.openapi.wm.impl)
    importPackage(com.intellij.ide.plugins)
    importPackage(com.intellij.openapi.extensions)

    const frameHelper = ProjectFrameHelper.getFrameHelper(component)
    const project = frameHelper.getProject()
    const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
    const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))

    const groups = service.groups()
    let count = -1
    for (let i = 0; i < groups.size(); i++) {
        if (groups.get(i).getName() == '$groupName') count = service.membersOf(groups.get(i).getId()).size()
    }
    count
    """,
    true,
)

/**
 * Dismisses the IDE's "Add File to Git" prompt, if it is up.
 *
 * Nothing to do with PrismTabs, and unavoidable here: the fixture project sits inside this
 * repository's own working copy (`.idea/vcs.xml` maps it), so creating a file in it makes the Git
 * integration offer to stage the new file — a **modal**, which then swallows the Delete keystroke
 * the project-view case depends on. Found exactly that way: the delete never happened and the
 * prompt this class is actually testing never appeared, with `Add File to Git` sitting in front of
 * everything.
 *
 * Cancelled rather than accepted, so a test run never stages anything into the real repository.
 * Absence is not a failure — the prompt only appears for a file Git does not already know about.
 */
private fun dismissAddToGitPrompt(remoteRobot: RemoteRobot) {
    val locator = byXpath("//div[@title='Add File to Git' and @class='MyDialog']")
    repeat(10) {
        val prompt = remoteRobot.findAll<DialogFixture>(locator).firstOrNull()
        if (prompt != null) {
            prompt.find<JButtonFixture>(JButtonFixture.byText("Cancel")).click()
            return
        }
        Thread.sleep(500)
    }
}
