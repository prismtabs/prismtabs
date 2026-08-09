package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import java.io.File

/**
 * Saves a real desktop screenshot to `build/reports/manual/[name].png` via `java.awt.Robot`, for
 * the manual verification pass to hand back for human review. Captures only the bounding box of
 * the sandbox IDE's own windows (frame plus any open popup/dialog), not the whole desktop — this
 * machine's real desktop has other, unrelated windows sharing the screen (this very session among
 * them), and a full-screen grab picked up slivers of them peeking out from behind the IDE frame.
 *
 * Deliberately not the offscreen-paint approach [RemoteRobotExtension]'s failure screenshot uses:
 * that technique exists specifically to dodge a Wayland deadlock (`Robot.createScreenCapture` blocks
 * forever waiting on an xdg-desktop-portal grant that never comes — see that class's doc comment),
 * but painting a `Window` directly bypasses whatever caching `JBEditorTabs`' custom tab-background
 * painting relies on: confirmed live on this Windows machine by comparing an offscreen-painted
 * screenshot against [IdeaFrame.pixelColorAtTabCenter] — the platform reported the correct
 * tab colour throughout, but the offscreen-painted screenshot never showed a tinted tab, selected or
 * not. Windows has no Wayland/portal problem, so a real `Robot.createScreenCapture` is both safe and
 * the more trustworthy capture here. Run off the EDT (`false`): unlike painting Swing components
 * directly, grabbing desktop pixels doesn't touch Swing's tree lock.
 */
fun RemoteRobot.saveScreenshot(name: String) {
    val bytes = callJs<ByteArray>(
        """
        importClass(java.awt.Toolkit)
        importClass(java.awt.Rectangle)
        importClass(java.awt.Window)
        importPackage(java.io)
        importPackage(javax.imageio)

        // `var`, not `const`/`let` — this Rhino does not block-scope a loop-body `const` (see
        // RemoteRobotExtension's doc comment for the measured failure mode).
        var bounds = null
        var windows = Window.getWindows()
        for (var w = 0; w < windows.length; w++) {
            var target = windows[w]
            if (!target.isShowing() || target.getWidth() <= 0 || target.getHeight() <= 0) continue
            try {
                var at = target.getLocationOnScreen()
                var rect = new Rectangle(at.x, at.y, target.getWidth(), target.getHeight())
                bounds = (bounds == null) ? rect : bounds.union(rect)
            } catch (ignored) { }
        }
        if (bounds == null) {
            var screen = Toolkit.getDefaultToolkit().getScreenSize()
            bounds = new Rectangle(0, 0, screen.width, screen.height)
        }

        // Not named `robot` — robot-server's own Rhino scope already binds that name (same
        // "redeclaration" family of quirk as the `log` collision documented in README's UI-testing
        // findings), confirmed live ("TypeError: redeclaration of var robot"). Fully-qualified
        // `java.awt.Robot` rather than `importClass`/`importPackage(java.awt)`, also confirmed live:
        // robot-server already has `org.assertj.swing.core.Robot` in scope, and a bare `Robot`
        // reference is "Ambiguous import" between the two.
        const awtRobot = new java.awt.Robot()
        const image = awtRobot.createScreenCapture(bounds)

        const baos = new ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        baos.toByteArray()
        """,
        false,
    )
    File("build/reports/manual").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
}
