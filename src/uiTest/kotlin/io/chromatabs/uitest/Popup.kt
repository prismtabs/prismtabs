package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * The most recently opened Swing popup list (`ListPopupImpl$MyList`, verified via `javap`) — used
 * for both `TabGroupPopup`'s root list and its nested steps. IntelliJ renders a `hasSubstep`
 * entry's child list as a second, still-visible cascading window, so picking `.last()` (not the
 * first match) is what selects the currently active nested step rather than its parent.
 *
 * Prefer [clickPopupItem] for clicking a known entry — see its doc for the race this one can lose.
 */
fun RemoteRobot.popupList(timeout: Duration = Duration.ofSeconds(10)): ContainerFixture {
    val locator = byXpath("//div[@class='MyList']")
    waitFor(timeout) { findAll<ContainerFixture>(locator).isNotEmpty() }
    return findAll<ContainerFixture>(locator).last()
}

/**
 * Clicks the popup entry labelled [text], waiting until some open popup list actually contains it.
 *
 * [popupList] alone loses a race when stepping *into* a nested step: it waits only for a `MyList`
 * to exist, and the parent list already does, so it can return the parent before the child window
 * has rendered — `findText` then throws `NoSuchElementException` for an entry that is about to
 * appear. Diagnosed live: the Phase 3 active-group test began failing on its "StickyGroup" row
 * (inside "Manage Groups…") once the Phase 4 tool window changed the editor's layout and timing,
 * despite that row being present and correct. Waiting on the *text* rather than on the list
 * removes the race regardless of how many cascading windows are open.
 */
fun RemoteRobot.clickPopupItem(text: String, timeout: Duration = Duration.ofSeconds(10)) {
    val locator = byXpath("//div[@class='MyList']")
    waitFor(timeout) {
        findAll<ContainerFixture>(locator).any { it.findAllText(text).isNotEmpty() }
    }
    retryingOnStaleComponent { findAll<ContainerFixture>(locator).last { it.findAllText(text).isNotEmpty() }.findText(text).click() }
}

/**
 * Right-clicks the editor tab for [path] and opens Chromatabs' "Tab Group…" swatch popup, retrying
 * the whole gesture if the context menu doesn't appear.
 *
 * The retry is not superstition: `remote-robot` drives a real `java.awt.Robot` at real screen
 * coordinates (see README "UI testing"), so a right-click issued while the tab strip is still
 * settling — right after `closeAllEditors()` + `openFile()`, say — lands before the tab occupies
 * its final position and no menu ever opens. That surfaces as a timeout on `actionMenuItem`, which
 * reads like a broken locator but isn't; re-issuing the gesture against a re-resolved tab fixes it.
 *
 * Lives here rather than being copy-pasted into each test class: the popup renders outside
 * `IdeFrameImpl`'s subtree, so it needs the unscoped `remoteRobot.find` this file already deals in.
 */
fun RemoteRobot.openTabGroupPopupOn(ideaFrame: IdeaFrame, path: String) {
    retryingOnStaleComponent {
        // The concrete runtime class is EditorTabLabel (fileEditor's TabLabel subclass), not
        // TabLabel itself — confirmed against the live component hierarchy, not javap alone.
        // Filtered by @accessiblename (the tab's file name; there is no @text on this component).
        val tab = ideaFrame.find<ComponentFixture>(
            byXpath("//div[@class='EditorTabLabel' and @accessiblename='$path']"),
            Duration.ofSeconds(20),
        )
        tab.rightClick()
        actionMenuItem("Tab Group…").click()
        popupList()
    }
}

/**
 * Retries [action] a few times when the popup mutates mid-interaction.
 *
 * Locating and clicking are separate round-trips to the IDE, so a cascading `ListPopup` can close
 * or repaint a window in between — the click then lands on a component that is no longer showing
 * and the IDE side throws (`IdeaSideException`, wrapping `Component.getLocationOnScreen`'s
 * "component must be showing on the screen" at `Component.java:2145`). Nothing about that is a
 * product defect; it is the cost of driving a real desktop, and re-resolving the fixture fixes it.
 */
private fun retryingOnStaleComponent(attempts: Int = 3, action: () -> Unit) {
    repeat(attempts - 1) {
        try {
            action()
            return
        } catch (_: Throwable) {
            Thread.sleep(500)
        }
    }
    action()
}

/**
 * Selects the `hasSubstep` row labelled [parent] by **hovering** it, then clicks [child] in the
 * substep that opens.
 *
 * Hovering, not clicking, because IntelliJ's `ListPopup` opens a `hasSubstep` row's child list on
 * *selection* — so by the time a test clicks the row, the child window is usually already open and
 * the click **closes** it again. Confirmed live by dumping every open `MyList` around the
 * interaction: right after "Manage Groups…" the tree already read
 * `[0]=<presets…> ;; [1]=DumpGroup ;; [2]=Rename…|Recolour…|Mark as Active Group|Delete Group`,
 * and clicking the `DumpGroup` row collapsed `[2]` back out of existence. Hovering selects the row
 * — which is all the substep needs — without toggling it, and still disambiguates between several
 * groups, unlike relying on whichever substep happened to auto-open.
 */
fun RemoteRobot.clickPopupSubstepItem(parent: String, child: String, timeout: Duration = Duration.ofSeconds(10)) {
    val locator = byXpath("//div[@class='MyList']")
    waitFor(timeout) { findAll<ContainerFixture>(locator).any { it.findAllText(parent).isNotEmpty() } }
    retryingOnStaleComponent {
        findAll<ContainerFixture>(locator).last { it.findAllText(parent).isNotEmpty() }.findText(parent).moveMouse()
        waitFor(timeout) { findAll<ContainerFixture>(locator).any { it.findAllText(child).isNotEmpty() } }
        findAll<ContainerFixture>(locator).last { it.findAllText(child).isNotEmpty() }.findText(child).click()
    }
}
