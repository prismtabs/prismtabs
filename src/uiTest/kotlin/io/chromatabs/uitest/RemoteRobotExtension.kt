package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.io.File

/**
 * Resolves a [RemoteRobot] test parameter pointed at `runIdeForUiTests` (see README "UI testing")
 * and, on failure, saves a screenshot to `build/reports` — the only debugging aid available once
 * the IDE under test has already moved on.
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
        // Dismiss anything a previous *failed* test left open. A half-finished TabGroupPopup keeps
        // a modal-ish cascade of ListPopup windows on screen, and those swallow the next test's
        // tab right-click — which then fails as a WaitForConditionTimeoutException on
        // `actionMenuItem("Tab Group…")`, i.e. looking exactly like a locator bug in a test that is
        // in fact fine. Three escapes covers TabGroupPopup's deepest nesting (root → group →
        // action).
        repeat(3) { Keyboard(remoteRobot).escape() }

        remoteRobot.runJs(
            """
            importClass(java.awt.Frame)
            const frames = Frame.getFrames()
            for (let i = 0; i < frames.length; i++) {
                const f = frames[i]
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

    override fun afterTestExecution(context: ExtensionContext) {
        if (context.executionException.isPresent) {
            val bytes = remoteRobot.callJs<ByteArray>(
                """
                importPackage(java.io)
                importPackage(javax.imageio)
                const screenShot = new java.awt.Robot().createScreenCapture(
                    new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize())
                )
                const baos = new ByteArrayOutputStream()
                ImageIO.write(screenShot, "png", baos)
                baos.toByteArray()
                """,
            )
            File("build/reports").apply { mkdirs() }
                .resolve("${context.requiredTestMethod.name}.png")
                .writeBytes(bytes)
        }
    }
}
