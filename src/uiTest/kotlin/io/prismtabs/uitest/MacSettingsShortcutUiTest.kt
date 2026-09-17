package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import java.time.Duration.ofSeconds

/**
 * Checklist item 7's macOS clause from docs/cross-platform-manual-verification.md: **Settings |
 * Tools | PrismTabs** must be reachable via **⌘,**, not only through the Settings menu item.
 *
 * Runs against the regular `runIdeForUiTests` sandbox like everything else — no special variant.
 * That is a measured decision, not an assumption: a "native macOS chrome" sandbox with the screen
 * menu bar left enabled was built and rejected, because it leaves `focusOwner` as `NONE` and AWT
 * then discards every key event (see the note in `build.gradle.kts`). ⌘, does not need that menu
 * bar anyway — it is an ordinary keymap binding, confirmed live by reading the active keymap:
 * `Mac OS X 10.5+`, `getActionIds(KeyStroke("meta COMMA"))` -> `[ShowSettings]`.
 *
 * This is the one macOS path the rest of the suite cannot cover: [IdeaFrame.openPrismTabsSettings]
 * calls `ShowSettingsUtil` directly, so it would still pass if the shortcut were unbound.
 *
 * Searching for the page rather than clicking down the Tools tree is also deliberate —
 * `remote-robot` handles tree navigation poorly (README "Why remote-robot, not IDE Starter +
 * Driver"), and the Settings search field takes focus on open, so no extra click is needed.
 */
@ExtendWith(RemoteRobotExtension::class)
class MacSettingsShortcutUiTest {
    init { StepsLogger.init() }

    @Test
    fun `Cmd+Comma opens Settings and PrismTabs is reachable there`(remoteRobot: RemoteRobot) {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"), "macOS-only checklist item")

        val keyboard = Keyboard(remoteRobot)
        remoteRobot.idea {
            // A stray modal left by an earlier failure would swallow the shortcut and make this
            // look like a broken keybinding, so start from a known-clean frame.
            repeat(3) { keyboard.escape() }

            keyboard.hotKey(KeyEvent.VK_META, KeyEvent.VK_COMMA)

            val settings = settingsDialog(timeout = ofSeconds(20))
            keyboard.enterText("PrismTabs")
            waitFor(ofSeconds(20)) { settings.findAllText("PrismTabs").isNotEmpty() }

            remoteRobot.saveScreenshot("09-cmd-comma-settings")
            keyboard.escape()
        }
    }
}
