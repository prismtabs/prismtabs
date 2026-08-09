package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * End-to-end coverage of [io.prismtabs.ui.TabGroupPaletteConfigurable] against a real, running
 * sandbox IDE — see README "UI testing" for how to launch `runIdeForUiTests` first, and
 * [IdeaFrame.exercisePaletteConfigurable] for why this drives the Configurable through direct
 * Swing/reflection calls rather than mouse-clicking the Settings tree and a native colour chooser.
 */
@ExtendWith(RemoteRobotExtension::class)
class TabGroupPaletteConfigurableUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `editing a preset's name applies to the palette service, and Restore Defaults reverts it`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            try {
                resetTabGroupPalette()

                val checkpoints = exercisePaletteConfigurable().associate {
                    val (label, value) = it.split(":", limit = 2)
                    label to value
                }

                assertEquals("Grey", checkpoints["initialLabel"], "expected the first row to start out as the built-in \"Grey\" preset")
                assertEquals("true", checkpoints["modifiedBeforeApply"], "editing the name field should make the Configurable modified")
                assertEquals("false", checkpoints["modifiedAfterApply"], "apply() should clear the modified flag")
                assertEquals("MyGrey", checkpoints["serviceLabelAfterApply"], "apply() should persist the edited name to TabGroupPaletteService")
                assertEquals("Grey", checkpoints["labelAfterRestoreDefaults"], "the Restore Defaults link should push the factory default back into the field")
                assertEquals("true", checkpoints["modifiedAfterRestoreDefaults"], "Restore Defaults should be seen as a pending change until applied")
                assertEquals("Grey", checkpoints["serviceLabelAfterRestoreApply"], "applying after Restore Defaults should drop the override")
            } finally {
                resetTabGroupPalette()
            }
        }
    }
}
