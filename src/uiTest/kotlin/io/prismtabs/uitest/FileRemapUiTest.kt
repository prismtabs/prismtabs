package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import java.time.Duration.ofSeconds

/**
 * An assignment follows its file through a rename or a move — [io.prismtabs.listener.TabGroupVfsListener]
 * end to end, against real VFS events, where `TabGroupServiceTest` can only exercise the remap itself.
 *
 * Every file here is a throwaway made by the test (see [createFixtureFile] for why), and every group
 * is reset *before* the files are deleted in `finally`: deleting a group's last member on disk offers
 * to delete the group, and a prompt left open would swallow the next test.
 */
@ExtendWith(RemoteRobotExtension::class)
class FileRemapUiTest {
    init {
        StepsLogger.init()
    }

    /** Renamed the way a user renames: Shift+F6 on the file's row in the Project view. */
    @Test
    fun `a file renamed from the Project view keeps its group, its tab colour and its row in the tool window`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                silenceAddToVcsConfirmation()
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                createFixtureFile("Before.txt")
                openFile("Before.txt")
                remoteRobot.createGroupViaPopup(this, "Before.txt", colour = "Blue", name = "Follows")

                val tree = projectViewTreeShowing("Before.txt")
                tree.clickRowWithText("Before.txt", true)
                Keyboard(remoteRobot).hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_F6)
                dialog("Rename")
                waitFor(ofSeconds(10), errorMessage = "the rename field never took focus: ${remoteRobot.focusOwner()}") {
                    remoteRobot.focusOwner().endsWith("@Rename")
                }
                remoteRobot.typeOverFocusedFieldAndEnter("After.txt")

                waitFor(ofSeconds(20), errorMessage = "the tab was not renamed: ${openTabNames()}") { openTabNames().contains("After.txt") }
                waitFor(ofSeconds(10)) { groupNameFor("After.txt") == "Follows" }
                assertEquals(1, memberCount("Follows"), "the old URL must not linger as a second member")
                waitFor(ofSeconds(10), errorMessage = "the renamed tab lost its colour") { tabRgb("After.txt") == themedPreset("blue") }

                hideProjectView()
                assertEquals(
                    listOf("After.txt"),
                    remoteRobot.toolWindowMemberRows("Follows").map { it.trim().substringBefore("  ") },
                )
            } finally {
                hideProjectView()
                hideTabGroupsToolWindow()
                resetTabGroups()
                deleteFixtureFile("Before.txt")
                deleteFixtureFile("After.txt")
            }
        }
    }

    @Test
    fun `a file moved to another directory keeps its group and colour, and the tool window shows where it went`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                silenceAddToVcsConfirmation()
                resetTabGroups()
                resetTabGroupPalette()
                hideTabGroupsToolWindow()
                createFixtureFile("Mover.txt")
                openFile("Mover.txt")
                remoteRobot.createGroupViaPopup(this, "Mover.txt", colour = "Green", name = "Moves")

                moveFixtureFile("Mover.txt", "moved/deeper")

                waitFor(ofSeconds(10)) { groupNameFor("moved/deeper/Mover.txt") == "Moves" }
                assertEquals(1, memberCount("Moves"))
                waitFor(ofSeconds(10), errorMessage = "the moved tab lost its colour") { tabRgb("Mover.txt") == themedPreset("green") }
                remoteRobot.expandToolWindowGroup("Moves")
                waitFor(ofSeconds(10), errorMessage = "the row must name the new directory, got ${toolWindowRowRendering()}") {
                    toolWindowRowRendering().getOrNull(1) == "filetype:PLAIN_TEXT|Mover.txt{bold}¦  moved/deeper{grey}"
                }
            } finally {
                hideTabGroupsToolWindow()
                resetTabGroups()
                deleteFixtureFile("moved")
                deleteFixtureFile("Mover.txt")
            }
        }
    }

    /** One VFS event for the directory, and every assignment nested under it has to follow. */
    @Test
    fun `renaming or moving a directory carries every assigned file under it`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                silenceAddToVcsConfirmation()
                resetTabGroups()
                createFixtureFile("dir-before/One.txt")
                createFixtureFile("dir-before/nested/Two.txt")
                assignFileToNewGroup("dir-before/One.txt", "DirA")
                assignFileToNewGroup("dir-before/nested/Two.txt", "DirB")

                renameFixtureFile("dir-before", "dir-after")
                waitFor(ofSeconds(10)) {
                    groupNameFor("dir-after/One.txt") == "DirA" && groupNameFor("dir-after/nested/Two.txt") == "DirB"
                }
                assertEquals(1, memberCount("DirA"))
                assertEquals(1, memberCount("DirB"))

                moveFixtureFile("dir-after", "relocated")
                waitFor(ofSeconds(10)) {
                    groupNameFor("relocated/dir-after/One.txt") == "DirA" &&
                        groupNameFor("relocated/dir-after/nested/Two.txt") == "DirB"
                }
                assertEquals(1, memberCount("DirA"))
                assertEquals(1, memberCount("DirB"))
            } finally {
                resetTabGroups()
                deleteFixtureFile("dir-before")
                deleteFixtureFile("dir-after")
                deleteFixtureFile("relocated")
            }
        }
    }

    /** A hidden group's memory of what to reopen is keyed by URL too, and must follow the rename. */
    @Test
    fun `a hidden group reopens a remembered tab under its new name after a rename`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                silenceAddToVcsConfirmation()
                resetTabGroups()
                createFixtureFile("Remembered.txt")
                openFile("Sample.kt")
                openFile("Remembered.txt")
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Sample.kt", "Remembered.txt") }
                assignFileToNewGroup("Sample.kt", "Shelf")
                addFileToGroup("Remembered.txt", "Shelf")
                setGroupHidden("Shelf", hidden = true)
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }

                renameFixtureFile("Remembered.txt", "Renamed.txt")
                waitFor(ofSeconds(10), errorMessage = "the memory did not follow the rename: ${rememberedTabUrlsOf("Shelf")}") {
                    rememberedTabUrlsOf("Shelf").map { it.substringAfterLast('/') } == listOf("Sample.kt", "Renamed.txt")
                }

                setGroupHidden("Shelf", hidden = false)
                waitFor(ofSeconds(10), errorMessage = "Show did not reopen the renamed file: ${openTabNames()}") {
                    openTabNames() == listOf("Sample.kt", "Renamed.txt")
                }
            } finally {
                resetTabGroups()
                deleteFixtureFile("Remembered.txt")
                deleteFixtureFile("Renamed.txt")
            }
        }
    }
}
