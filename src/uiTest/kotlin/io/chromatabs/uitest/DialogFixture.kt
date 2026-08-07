package io.chromatabs.uitest

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

@FixtureName("Dialog")
class DialogFixture(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent)
