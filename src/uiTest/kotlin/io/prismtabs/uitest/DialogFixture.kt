package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.FixtureName
import com.intellij.remoterobot.search.locators.byXpath
import java.time.Duration

/**
 * `MyDialog` (verified via `javap` on `DialogWrapperPeerImpl`) is the JDialog peer every
 * `DialogWrapper`-based dialog uses, including `Messages.showInputDialog` — not specific to this
 * plugin, so this xpath works for any platform dialog.
 */
fun ContainerFixture.dialog(
    title: String,
    timeout: Duration = Duration.ofSeconds(20),
    function: DialogFixture.() -> Unit = {},
): DialogFixture =
    find<DialogFixture>(byXpath("title $title", "//div[@title='$title' and @class='MyDialog']"), timeout).apply(function)

/**
 * Whether a dialog titled [title] is showing *right now* — the check for asserting one is **not**,
 * where [dialog]'s waiting `find` would block for its whole timeout before failing.
 */
fun ContainerFixture.hasDialog(title: String): Boolean =
    findAll<DialogFixture>(byXpath("title $title", "//div[@title='$title' and @class='MyDialog']")).isNotEmpty()

/**
 * The Settings dialog. It is not a [dialog] on every platform version: 2024.2 shows a modal
 * `MyDialog` titled "Settings", while 2026.2 shows a non-modal `NonModalWindowWrapper$FloatDialog`
 * titled "Settings – <project name>" (an en dash), so both shapes are matched.
 */
private val SETTINGS_DIALOG = byXpath(
    "title Settings",
    "//div[(@class='MyDialog' or @class='FloatDialog') and (@title='Settings' or starts-with(@title, 'Settings – '))]",
)

fun ContainerFixture.settingsDialog(timeout: Duration = Duration.ofSeconds(20)): DialogFixture =
    find<DialogFixture>(SETTINGS_DIALOG, timeout)

/** Whether the Settings dialog is showing *right now*; see [hasDialog] and [settingsDialog]. */
fun ContainerFixture.hasSettingsDialog(): Boolean = findAll<DialogFixture>(SETTINGS_DIALOG).isNotEmpty()

@FixtureName("Dialog")
class DialogFixture(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent)
