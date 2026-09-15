package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitFor
import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * What survives an IDE restart, without restarting: the state is saved the way the IDE saves it on
 * exit ([IdeaFrame.forceSaveSettings]), and the files the platform wrote are then loaded into fresh
 * service instances ([IdeaFrame.tabGroupsAsRestartedFromDisk], [IdeaFrame.paletteAsRestartedFromDisk]) —
 * the two halves of a restart that concern this plugin. What a real restart adds on top is the
 * process lifecycle, which is the platform's; `WindowsManualVerificationUiTest` walks that part by
 * hand.
 */
@ExtendWith(RemoteRobotExtension::class)
class PersistenceUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `groups, members, colours, remembered tabs, an emptied group and the palette survive a restart, the active group does not`(
        remoteRobot: RemoteRobot,
    ) {
        remoteRobot.idea {
            try {
                resetTabGroups()
                resetTabGroupPalette()
                openFile("Second.kt")
                openFile("Sample.kt")
                waitFor(ofSeconds(10)) { openTabNames() == listOf("Second.kt", "Sample.kt") }
                assignFileToNewGroup("Sample.kt", "Kept", lightRgb = 0x111111, darkRgb = 0x222222)
                addFileToGroup("Second.kt", "Kept")

                // Hiding records the open tabs in strip order and closing them all leaves the group
                // hidden — the state that, after a restart, only the remembered list can undo.
                setGroupHidden("Kept", hidden = true)
                waitFor(ofSeconds(10)) { openTabNames().isEmpty() }

                assignFileToNewGroup("Third.kt", "Emptied")
                clearGroupFor("Third.kt")
                toggleActiveGroup("Kept")
                assertEquals("Kept", activeGroupName(), "precondition: the running IDE has an active group")
                editPalettePreset(3, "Reviews", lightRgb = 0xABCDEF, darkRgb = 0x123456)

                forceSaveSettings()

                assertEquals(
                    listOf(
                        "Kept|${0x111111}|${0x222222}|Sample.kt,Second.kt|Second.kt,Sample.kt",
                        "Emptied|${0x4285F4}|${0x8AB4F8}||",
                        "active=none",
                    ),
                    tabGroupsAsRestartedFromDisk(),
                )
                assertEquals(
                    TabGroupPalette.PRESETS.mapIndexed { index, preset ->
                        if (index == 3) "Reviews|${0xABCDEF}|${0x123456}"
                        else "${preset.label}|${preset.light.rgb and 0xFFFFFF}|${preset.dark.rgb and 0xFFFFFF}"
                    },
                    paletteAsRestartedFromDisk(),
                )
            } finally {
                clearActiveGroup()
                setGroupHidden("Kept", hidden = false)
                resetTabGroupPalette()
            }
        }
    }
}
