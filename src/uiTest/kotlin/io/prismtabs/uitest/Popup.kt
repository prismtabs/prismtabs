package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
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
 * Right-clicks the editor tab for [path] and opens PrismTabs' "Tab Group…" swatch popup, retrying
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
 * The popup's whole group-creation flow: open it on [path], click the [colour] dot, replace the
 * pre-filled name with [name], accept, and **wait for the assignment to land** before returning.
 *
 * The wait is load-bearing, not padding — the name dialog is dispatched from the popup's
 * `getFinalRunnable()`, so `service.assign` happens after the dialog closes. Without the barrier a
 * caller's next `openTabGroupPopupOn` can race it and miss, say, the "Remove from Group" row that
 * only appears once the file is assigned.
 *
 * Lives here beside [openTabGroupPopupOn] rather than in [IdeaFrame]: like the rest of this file it
 * needs the unscoped `remoteRobot.find` that popup and dialog windows (outside `IdeFrameImpl`'s
 * subtree) require. Contrast [IdeaFrame.assignFileToNewGroup], which bypasses the UI entirely for
 * tests whose subject isn't the popup.
 */
fun RemoteRobot.createGroupViaPopup(
    ideaFrame: IdeaFrame,
    path: String,
    colour: String,
    name: String,
    timeout: Duration = Duration.ofSeconds(10),
) {
    openTabGroupPopupOn(ideaFrame, path)
    clickPopupItem(colour, timeout)
    ideaFrame.dialog("New Tab Group") {
        find<JTextFieldFixture>(JTextFieldFixture.byType()).text = name
        find<JButtonFixture>(JButtonFixture.byText("OK")).click()
    }
    waitFor(timeout) { ideaFrame.groupNameFor(path) == name }
}

/**
 * JS prelude that leaves three names bound for the helpers below: `list` (the open popup's
 * `ListPopupImpl$MyList`), `renderer` (its cell renderer) and `separator` (the renderer's single,
 * shared `mySeparatorComponent`).
 *
 * Two unwrapping steps are needed to reach the separator, both found by probing the live IDE rather
 * than guessed: `JList.getCellRenderer()` hands back an `ExpandedItemListCellRendererWrapper`, not
 * the popup renderer itself; and `mySeparatorComponent` is a protected field declared on
 * `GroupedElementsRenderer`, several levels above the concrete renderer class — hence the walk up
 * `getSuperclass()`.
 *
 * A `val`, not a `const val`: it embeds `${'$'}` to emit a literal `$` in `ListPopupImpl${'$'}MyList`,
 * and a `const val` cannot carry an interpolation.
 */
private val POPUP_SEPARATOR_PRELUDE = """
    function findList(c) {
        if (c.getClass().getName().indexOf('ListPopupImpl${'$'}MyList') >= 0) return c
        if (!(c instanceof java.awt.Container)) return null
        var kids = c.getComponents()
        for (var i = 0; i < kids.length; i++) {
            var r = findList(kids[i])
            if (r != null) return r
        }
        return null
    }

    // `var` in the loop body, never `const`/`let` — see RemoteRobotExtension's doc for the
    // measured Rhino scoping failure this avoids.
    var list = null
    var windows = java.awt.Window.getWindows()
    for (var w = 0; w < windows.length; w++) {
        var t = windows[w]
        if (!t.isShowing()) continue
        var found = findList(t)
        if (found != null) list = found
    }
    if (list == null) throw new java.lang.IllegalStateException('no open ListPopupImpl${'$'}MyList found')

    var renderer = com.intellij.ui.ExpandedItemListCellRendererWrapper.unwrap(list.getCellRenderer())
    var cls = renderer.getClass()
    var separator = null
    while (cls != null && separator == null) {
        var fields = cls.getDeclaredFields()
        for (var i = 0; i < fields.length; i++) {
            if (fields[i].getName() == 'mySeparatorComponent') {
                fields[i].setAccessible(true)
                separator = fields[i].get(renderer)
            }
        }
        cls = cls.getSuperclass()
    }
    if (separator == null) {
        throw new java.lang.IllegalStateException(
            'no mySeparatorComponent on ' + renderer.getClass().getName())
    }
"""

/**
 * Every row of the open popup as `caption to separatorBottomInset`, in model order — the pair
 * [TabGroupPopupUiTest] uses to prove the "New Group" heading gets extra room beneath it *and* that
 * the plain divider above "Add to Group…" does not.
 *
 * Both halves matter, and testing them together is the point: `GroupedElementsRenderer` builds one
 * separator component and reuses it for every row that has one, so the obvious implementation
 * (widen it once at creation) silently pushes "Add to Group…" away from its divider too.
 *
 * Each row is rendered explicitly rather than read off a painted popup, because the border is
 * applied during `getListCellRendererComponent` — driving the renderer directly is what makes the
 * per-row decision observable at all.
 *
 * The shared separator reports each row's *own* caption, not a stale one, which is what makes this
 * reading trustworthy: `GroupedElementsRenderer.configureComponent` calls `setCaption` on every row
 * unconditionally (immediately after `setVisible(hasSeparatorAbove)`), and `getCaptionAboveOf`
 * yields `""` both for a row with no separator and for a bare `ListSeparator()` — whose no-arg
 * constructor delegates to `ListSeparator("")`, not null. All checked with `javap` per CLAUDE.md's
 * recipe.
 */
fun RemoteRobot.popupSeparatorInsetsByCaption(): List<Pair<String, Int>> {
    val raw = callJs<String>(
        """
        $POPUP_SEPARATOR_PRELUDE
        var model = list.getModel()
        var out = []
        for (var i = 0; i < model.getSize(); i++) {
            renderer.getListCellRendererComponent(list, model.getElementAt(i), i, false, false)
            var caption = separator.getCaption()
            out.push((caption == null ? '' : caption) + '=' + separator.getInsets().bottom)
        }
        out.join('|')
        """,
        true,
    )
    // filter first: an empty model would otherwise make split() yield one blank row and fail the
    // parse. Split on the *last* '=' so a caption containing one survives intact.
    return raw.split('|').filter { it.isNotEmpty() }.map { row ->
        row.substringBeforeLast('=') to row.substringAfterLast('=').toInt()
    }
}

/**
 * Reads the open popup's separator caption colour and the list's own item foreground, as
 * `"r,g,b"` pairs — the two values [TabGroupPopupUiTest] compares to prove the "New Group" caption
 * isn't painted in the platform's muted `separatorTextColor`.
 *
 * Goes at the live component rather than at screenshot pixels for the reason spelled out at that
 * test: subpixel antialiasing makes sampled glyph pixels an unreliable read of the colour actually
 * set. `GroupHeaderSeparator.paintComponent` does `g.setColor(getTextForeground())` immediately
 * before `drawString` (checked with `javap` per CLAUDE.md's recipe), so this field *is* what gets
 * painted.
 *
 * Two unwrapping steps are needed to reach it, both found by probing the live IDE rather than
 * guessed: `JList.getCellRenderer()` hands back an `ExpandedItemListCellRendererWrapper`, not the
 * popup renderer itself; and `mySeparatorComponent` is a protected field on `GroupedElementsRenderer`,
 * several levels up from the concrete renderer class, hence the walk up `getSuperclass()`.
 */
fun RemoteRobot.popupSeparatorAndListForeground(): Pair<String, String> {
    val raw = callJs<String>(
        """
        $POPUP_SEPARATOR_PRELUDE
        function rgb(c) { return c.getRed() + ',' + c.getGreen() + ',' + c.getBlue() }
        rgb(separator.getTextForeground()) + '|' + rgb(com.intellij.util.ui.UIUtil.getListForeground())
        """,
        true,
    )
    val (caption, listForeground) = raw.split('|')
    return caption to listForeground
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
