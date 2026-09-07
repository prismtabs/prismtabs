package io.prismtabs.listener

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.awt.datatransfer.Transferable
import javax.swing.JTextField
import javax.swing.TransferHandler
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Everything about [PrismTabsUnloadListener] that fails *silently*: a listener that is never
 * registered, a registered listener that never recognises its own plugin, and a release step that
 * runs but drops nothing. In every case the class loader survives and the only symptom is an
 * "unable to unload" dialog on somebody else's machine weeks later — exactly the failure this
 * listener exists to prevent. The last of the three is not hypothetical: the first version of
 * [PrismTabsUnloadListener.releaseClipboard] tested the clipboard's contents for a type they can
 * never have and returned early every single time.
 *
 * The `plugin.xml` assertions read `src/main/resources`' own copy off the test classpath, like [
 * io.prismtabs.license.ProductDescriptorTest], so it asserts on the file a developer edits rather
 * than on `patchPluginXml`'s output.
 */
class PrismTabsUnloadListenerTest {

    private val root: Element = run {
        val xml = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml is not on the test classpath"
        }
        xml.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }.documentElement
    }

    @Test
    fun `the listener's plugin id is the one plugin xml declares`() {
        val declared = root.getElementsByTagName("id").item(0).textContent
        assertEquals(declared, PrismTabsUnloadListener.PLUGIN_ID.idString)
    }

    @Test
    fun `plugin xml registers the listener against the dynamic-plugin topic`() {
        val listeners = root.getElementsByTagName("applicationListeners")
        assertEquals(1, listeners.length, "expected exactly one <applicationListeners> in plugin.xml")

        val entries = (listeners.item(0) as Element).getElementsByTagName("listener")
        val registered = (0 until entries.length)
            .map { entries.item(it) as Element }
            .map { it.getAttribute("class") to it.getAttribute("topic") }

        assertEquals(
            listOf(PrismTabsUnloadListener::class.java.name to "com.intellij.ide.plugins.DynamicPluginListener"),
            registered,
        )
    }

    /**
     * The retention this release exists for, reproduced end to end: copying out of a Swing text
     * field leaves a transferable on the clipboard that holds the [javax.swing.text.JTextComponent]
     * itself, so a disposed dialog's field — and everything it reaches, which for one of our
     * dialogs is this plugin's class loader — stays alive until something re-publishes the
     * clipboard.
     *
     * The class-loader assertion is the other half, and its own regression: the first version of
     * this release re-published a copy that was a class of this plugin's, which pinned the loader
     * exactly as firmly as the transferable it had just replaced. Whatever is left behind has to
     * come from a loader that outlives the plugin, and `null` is `java.desktop`'s.
     *
     * Runs against a clipboard of its own rather than the desktop's: the retention is a property of
     * [java.awt.datatransfer.Clipboard], the assertions are exact, and the developer's own
     * clipboard is left alone.
     */
    @Test
    fun `releasing a clipboard drops the text field a copy left on it`() {
        val clipboard = Clipboard("prismtabs test clipboard")
        val text = "prismtabs clipboard release"
        var field: JTextField? = JTextField(text).apply { selectAll() }
        val handler = checkNotNull(field?.transferHandler) { "Swing installed no transfer handler" }
        handler.exportToClipboard(field, clipboard, TransferHandler.COPY)

        val reference = WeakReference(field)
        field = null

        PrismTabsUnloadListener().releaseClipboard(clipboard)

        assertTrue(collectable(reference), "the copied text field is still reachable from the clipboard")
        assertNull(
            clipboard.getContents(null).javaClass.classLoader,
            "what is left on the clipboard must be a JDK class, or it pins the plugin's loader",
        )
        assertEquals(text, clipboard.getData(DataFlavor.stringFlavor))
    }

    /**
     * Contents that are more than text are not ours to rewrite: an image (or a file list, or one of
     * the IDE's rich transferables) would lose everything but a string it does not have, so it goes
     * back untouched — and the owner, the other half of the retention, goes anyway.
     */
    @Test
    fun `releasing a clipboard leaves contents that are not plain text alone`() {
        val clipboard = Clipboard("prismtabs test clipboard")
        val contents = ImageContents(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
        val lostOwnership = CountDownLatch(1)
        clipboard.setContents(contents) { _, _ -> lostOwnership.countDown() }

        PrismTabsUnloadListener().releaseClipboard(clipboard)

        assertSame(contents, clipboard.getContents(null), "the image was rewritten as something else")
        assertTrue(
            lostOwnership.await(10, TimeUnit.SECONDS),
            "the owner was never displaced — `Clipboard.setContents` hands it to the event thread",
        )
    }

    /**
     * The other half of the release, on the clipboard that actually shows it: `DefaultCaret` owns
     * the primary selection through a [ClipboardOwner] of its own, so displacing that owner — which
     * is what `lostOwnership` firing proves — is the whole point, and the text has to survive
     * untouched because it is the user's middle-click paste buffer.
     *
     * Skipped where there is no primary selection to own: headless (CI) and every non-Linux
     * desktop. That is the same platform limit the production code returns early on.
     */
    @Test
    fun `releasing the primary selection displaces its owner and keeps the text`() {
        val clipboard = primarySelection()
        val text = "prismtabs primary-selection release"
        val lostOwnership = CountDownLatch(1)
        val caretLikeOwner = ClipboardOwner { _, _ -> lostOwnership.countDown() }

        withContentsRestored(clipboard) {
            clipboard.setContents(StringSelection(text), caretLikeOwner)

            PrismTabsUnloadListener().releaseClipboard(clipboard)

            assertTrue(
                lostOwnership.await(10, TimeUnit.SECONDS),
                "the owner was never displaced, so a caret's Handler would still pin its text field",
            )
            assertEquals(text, clipboard.getContents(null).getTransferData(DataFlavor.stringFlavor))
        }
    }

    /**
     * The JDK behaviour [PrismTabsUnloadListener.releaseClipboard] tells ownership by:
     * `SunClipboard` hands back its stored `contents` field while this JVM owns the clipboard, and
     * builds a fresh `ClipboardTransferable` per call when another application does. Pinned here
     * because the ownership test this replaced was also a reading of `SunClipboard`, was wrong, and
     * failed by doing nothing at all.
     */
    @Test
    fun `the primary selection reads back as one instance while this JVM owns it`() {
        val clipboard = primarySelection()

        withContentsRestored(clipboard) {
            clipboard.setContents(StringSelection("prismtabs ownership probe"), null)

            assertSame(clipboard.getContents(null), clipboard.getContents(null))
        }
    }

    /** The primary selection, skipping the test — assumption recorded — where there is none. */
    private fun primarySelection(): Clipboard {
        val clipboard = if (GraphicsEnvironment.isHeadless()) null else Toolkit.getDefaultToolkit().systemSelection
        assumeTrue(clipboard != null, "no primary selection: headless, or not an X11/Wayland session")
        return checkNotNull(clipboard)
    }

    /**
     * Puts the developer's own selection text back after [body]. The primary selection is shared
     * with every other window on the desktop, and a middle-click paste after a test run should
     * still produce what it produced before one; ownership cannot be handed back, but the text can.
     */
    private fun withContentsRestored(clipboard: Clipboard, body: () -> Unit) {
        val previous = runCatching { clipboard.getData(DataFlavor.stringFlavor) as String }.getOrDefault("")
        try {
            body()
        } finally {
            clipboard.setContents(StringSelection(previous), null)
        }
    }

    /** A clipboard content with a flavour [PrismTabsUnloadListener.releaseClipboard] must not touch. */
    private class ImageContents(private val image: Image) : Transferable {

        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any = image
    }

    /** Whether [reference] clears once the collector is given a few chances to run. */
    private fun collectable(reference: WeakReference<*>): Boolean {
        repeat(50) {
            if (reference.get() == null) return true
            System.gc()
            Thread.sleep(20)
        }
        return reference.get() == null
    }
}
