package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * The most recently opened Swing popup list (`ListPopupImpl$MyList`, verified via `javap`) — used
 * for both `TabGroupPopup`'s root list and its nested steps. IntelliJ renders a `hasSubstep`
 * entry's child list as a second, still-visible cascading window, so picking `.last()` (not the
 * first match) is what selects the currently active nested step rather than its parent.
 */
fun RemoteRobot.popupList(timeout: Duration = Duration.ofSeconds(10)): ContainerFixture {
    val locator = byXpath("//div[@class='MyList']")
    waitFor(timeout) { findAll<ContainerFixture>(locator).isNotEmpty() }
    return findAll<ContainerFixture>(locator).last()
}
