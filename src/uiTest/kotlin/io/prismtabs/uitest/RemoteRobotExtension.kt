package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.opentest4j.TestAbortedException
import java.io.File

/**
 * Resolves a [RemoteRobot] test parameter pointed at `runIdeForUiTests` (see README "UI testing")
 * and, on failure — but not on a skip, see [afterTestExecution] — saves a screenshot to
 * `build/reports`, the only debugging aid available once the IDE under test has already moved on.
 *
 * A test that passes its own assertions still fails if PrismTabs logged an error (`SEVERE`) to
 * `idea.log` while it ran — see [failOnPrismTabsErrors].
 *
 * [beforeTestExecution] also entitles the plugin's paid features before every test — see
 * [IdeaFrame.simulateEntitled]. Since `plugin.xml` gained its `<product-descriptor>` the sandbox
 * boots as a paid build holding no licence, so without this the whole suite would run against the
 * free tier: the tool window would render its upsell panel instead of a tree, and every
 * collapse/expand, active-group and palette-editing test would fail on a paywall rather than on
 * anything it was written to catch. Doing it here rather than per-class also makes it a backstop —
 * a paywall test that dies before its `finally` can no longer leave the plugin locked for whatever
 * runs next.
 *
 * [beforeTestExecution] de-iconifies and raises the sandbox IDE's frame before every test: this
 * machine's sandbox IDE renders on the live desktop, not an isolated virtual display (see README
 * "UI testing"), and something on it — a previous run's aborted client, the window manager, no
 * confirmed single cause — can leave the frame minimised between test runs. `remote-robot` drives a
 * real `java.awt.Robot` at real screen coordinates, so a minimised (or merely background) frame
 * makes every click land on whatever window is actually on top instead, which fails with the exact
 * same `WaitForConditionTimeoutException` as a genuine locator bug — confirmed live by finding
 * `iconified="1"` on `IdeFrameImpl` in a `curl :8082` dump at the moment of a failure. Fixed once
 * here rather than per-test.
 */
class RemoteRobotExtension : BeforeTestExecutionCallback, AfterTestExecutionCallback, ParameterResolver {

    private val remoteRobot = RemoteRobot(System.getProperty("remote-robot-url") ?: "http://127.0.0.1:8082")

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext) =
        parameterContext.parameter.type == RemoteRobot::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        remoteRobot

    override fun beforeTestExecution(context: ExtensionContext) {
        context.getStore(NAMESPACE).put(LOG_OFFSET, remoteRobot.ideaLogLength())

        // Dismiss anything a previous *failed* test left open. A half-finished TabGroupPopup keeps
        // a modal-ish cascade of ListPopup windows on screen, and those swallow the next test's
        // tab right-click — which then fails as a WaitForConditionTimeoutException on
        // `actionMenuItem("Tab Group…")`, i.e. looking exactly like a locator bug in a test that is
        // in fact fine. Three escapes covers TabGroupPopup's deepest nesting (root → group →
        // action).
        repeat(3) { Keyboard(remoteRobot).escape() }

        // Before the frame work below, not after: a locked plugin is what makes a test fail in the
        // most misleading way available (an empty tool window reads as a missing group, not as a
        // paywall), so it is the first thing put right.
        remoteRobot.simulateEntitled()

        remoteRobot.runJs(
            """
            importClass(java.awt.Frame)
            const frames = Frame.getFrames()
            for (let i = 0; i < frames.length; i++) {
                // `var`, deliberately, not `const`/`let`: robot-server's Rhino does not give a
                // loop-body `const` per-iteration block scope. It binds the name once at script
                // level and never rebinds it, so the variable stays stuck on iteration 0's element
                // for the whole loop. Measured directly against this runtime — indexing the very
                // same array inline gave `SharedOwnerFrame, IdeFrameImpl, MyDialog,
                // HeavyWeightWindow` while a loop-body `const` gave `SharedOwnerFrame` four times.
                // With `const` here, `f` was always frames[0] (Swing's hidden SharedOwnerFrame), so
                // the IdeFrameImpl test never matched and this whole de-iconify/raise block was a
                // silent no-op for as long as it has existed. `var` (or indexing inline) is rebound
                // correctly every iteration. Note this was a genuine bug but is *not* the cause of
                // the Wayland right-click failures documented in README "These tests need an X11
                // display" — there the injected input never reaches the IDE at all.
                var f = frames[i]
                if (f.getClass().getName().indexOf('IdeFrameImpl') === -1) continue
                f.setExtendedState(Frame.NORMAL)
                f.setAlwaysOnTop(true)
                f.toFront()
                f.setAlwaysOnTop(false)
            }
            """,
            true,
        )
    }

    /**
     * Saves a failure screenshot by **painting the IDE's own windows** offscreen — deliberately not
     * by grabbing the desktop with `java.awt.Robot.createScreenCapture`, which is what this used to
     * do and which turns any single test failure into a dead test run on this machine.
     *
     * `Robot.createScreenCapture` cannot read a Wayland compositor's framebuffer through XWayland,
     * so JDK 21 routes it to `sun.awt.screencast.ScreencastHelper` — the xdg-desktop-portal
     * ScreenCast (PipeWire) API — which blocks until the portal answers, and never returns when
     * nothing does. Confirmed by `jstack`ing the wedged sandbox IDE mid-failure: the robot server's
     * request thread sat in `ScreencastHelper.getRGBPixelsImpl` (native).
     *
     * The damage is out of all proportion to a debugging aid, because robot-server runs the request
     * inline on Ktor's **single** Netty event-loop thread (`eventLoopGroupProxy-4-1 @call-handler`,
     * same dump). Blocking it stops the server answering *anything* — so every later test dies in
     * [beforeTestExecution]'s `escape()` at okhttp's 60s timeout, which reads like a suite-wide
     * collapse rather than the one real failure it is. A client-side timeout would not have helped:
     * the IDE-side thread stays wedged regardless.
     *
     * Painting is the fix rather than a Wayland-only branch, because it is simply the better
     * capture here: it runs on the EDT with no `Robot` and no portal, it can't block, it works
     * headlessly, and it photographs the IDE alone instead of whatever else shares the desktop.
     * `paint` (not `printAll`) matches what `remote-robot`'s own painting-mode screenshot does
     * against this platform.
     *
     * `Window.getWindows()` is the right list: measured live it holds the frame *and* the popup and
     * dialog windows (`SharedOwnerFrame, IdeFrameImpl, DialogWrapperPeerImpl$MyDialog,
     * Popup$HeavyWeightWindow`), in creation order — so popups, created after the frame, paint last
     * and therefore on top, which is what a failing popup test needs to show.
     */
    override fun afterTestExecution(context: ExtensionContext) {
        val outcome = context.executionException.orElse(null)
        if (outcome == null) {
            failOnPrismTabsErrors(context)
            return
        }
        // A skipped test is not a failed one. JUnit signals an `assumeTrue` opt-out by throwing
        // TestAbortedException, which arrives in executionException exactly like a real failure —
        // so without this guard every self-gating class photographs a perfectly idle IDE into
        // build/reports under its own test name. That is not merely wasted work: `ui-tests.yml`
        // uploads build/reports as an artefact, so a PNG named after a test that never ran is
        // waiting there for whoever opens it, reading as a failure that did not happen. Measured on
        // the 2026-09-06 Windows pass — all three designed skips each left one behind:
        // MacSettingsShortcutUiTest off macOS, and WindowsManualVerificationUiTest's two cases
        // without `-Dprismtabs.manualVerification=true`.
        if (outcome is TestAbortedException) return
        val bytes = remoteRobot.callJs<ByteArray>(
            """
            importPackage(java.awt)
            importPackage(java.awt.image)
            importPackage(java.io)
            importPackage(javax.imageio)

            const screen = Toolkit.getDefaultToolkit().getScreenSize()
            const image = new BufferedImage(screen.width, screen.height, BufferedImage.TYPE_INT_RGB)
            const canvas = image.createGraphics()
            canvas.setColor(Color.DARK_GRAY)
            canvas.fillRect(0, 0, screen.width, screen.height)

            // `var` in the loop body below, never `const`/`let` — see the note in
            // [beforeTestExecution]: a loop-body `const` is bound once and stays on iteration 0's
            // element in this Rhino, which silently reduced this whole capture to a blank image.
            var windows = Window.getWindows()
            for (var w = 0; w < windows.length; w++) {
                var target = windows[w]
                if (!target.isShowing() || target.getWidth() <= 0 || target.getHeight() <= 0) continue
                // A window can be disposed between the isShowing() check and the paint; that throws
                // out of getLocationOnScreen rather than returning null, and one such window must
                // not cost us the whole screenshot.
                try {
                    var at = target.getLocationOnScreen()
                    var pen = canvas.create(at.x, at.y, target.getWidth(), target.getHeight())
                    try { target.paint(pen) } finally { pen.dispose() }
                } catch (ignored) { }
            }
            canvas.dispose()

            const baos = new ByteArrayOutputStream()
            ImageIO.write(image, "png", baos)
            baos.toByteArray()
            """,
            // Painting Swing components off the EDT races the IDE's own repaints and can deadlock
            // on Swing's tree lock; robot-server runs the script on the EDT when asked to.
            true,
        )
        File("build/reports").apply { mkdirs() }
            .resolve("${context.requiredTestMethod.name}.png")
            .writeBytes(bytes)
    }

    /**
     * Fails a test that passed its own assertions but left a PrismTabs error (`SEVERE`) in `idea.log` —
     * the "no stray errors" check a manual pass makes by watching the log, made for every automated
     * flow instead. Only this test's slice of the log is read, from where [beforeTestExecution] noted
     * its length, and only entries that name the plugin count: the sandbox has errors of its own that
     * are documented in README "Expected sandbox noise".
     *
     * Skipped for a test that already failed, whose own failure is the more useful report.
     */
    private fun failOnPrismTabsErrors(context: ExtensionContext) {
        val offset = context.getStore(NAMESPACE).get(LOG_OFFSET, Long::class.javaObjectType) ?: return
        val errors = prismTabsErrors(remoteRobot.ideaLogFrom(offset))
        if (errors.isNotEmpty()) {
            throw AssertionError("PrismTabs logged ${errors.size} error(s) during this test:\n\n" + errors.joinToString("\n\n"))
        }
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(RemoteRobotExtension::class.java)
        const val LOG_OFFSET = "ideaLogOffset"
    }
}
