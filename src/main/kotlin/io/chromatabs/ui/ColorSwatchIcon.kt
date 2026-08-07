package io.chromatabs.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Color as AwtColor
import javax.swing.Icon

/**
 * A small filled-circle swatch for [TabGroupPopup] list rows. The border exists because the
 * palette's pale light-theme variants (Grey, Yellow) would otherwise disappear against a
 * light-theme popup background — see the plan's Phase 2 note on this. `JBColor` is safe to use
 * here unconditionally: an `Icon` only ever paints inside a live IDE with an active LAF, never
 * under the plain-JUnit harness the model/service layers are tested with.
 */
internal class ColorSwatchIcon(private val color: AwtColor) : Icon {

    private val boxSize = 16
    private val dotSize = 10

    override fun getIconWidth(): Int = JBUI.scale(boxSize)
    override fun getIconHeight(): Int = JBUI.scale(boxSize)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val d = JBUI.scale(dotSize)
            val offset = (JBUI.scale(boxSize) - d) / 2
            g2.color = color
            g2.fillOval(x + offset, y + offset, d, d)
            g2.color = BORDER
            g2.drawOval(x + offset, y + offset, d - 1, d - 1)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val BORDER: JBColor = JBColor(AwtColor(0, 0, 0, 40), AwtColor(255, 255, 255, 50))
    }
}
