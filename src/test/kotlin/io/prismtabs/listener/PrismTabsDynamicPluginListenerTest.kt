package io.prismtabs.listener

import io.prismtabs.model.TabGroup
import io.prismtabs.provider.TabGroupColorProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.awt.datatransfer.Transferable
import javax.swing.JTextField
import javax.swing.TransferHandler
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Everything about [PrismTabsDynamicPluginListener] that fails *silently*: a listener that is
 * never registered, a registered listener that never recognises its own plugin, and a release step
 * that runs but drops nothing. In every case the class loader survives and the only symptom is an
 * "unable to unload" dialog on somebody else's machine weeks later — exactly the failure this
 * listener exists to prevent. The last of the three is not hypothetical: the first version of
 * [PrismTabsDynamicPluginListener.releaseClipboard] tested the clipboard's contents for a type they
 * can never have and returned early every single time.
 *
 * The tab-colour half of an unload is here too, for the same reason: forgetting it costs nobody a
 * stack trace, it just leaves the strip painted by a plugin that is gone.
 *
 * The `plugin.xml` assertions read `src/main/resources`' own copy off the test classpath, like [
 * io.prismtabs.license.ProductDescriptorTest], so it asserts on the file a developer edits rather
 * than on `patchPluginXml`'s output.
 */
class PrismTabsDynamicPluginListenerTest {

    private val root: Element = run {
        val xml = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml is not on the test classpath"
        }
        xml.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }.documentElement
    }

    @Test
    fun `the listener's plugin id is the one plugin xml declares`() {
        val declared = root.getElementsByTagName("id").item(0).textContent
        assertEquals(declared, PrismTabsDynamicPluginListener.PLUGIN_ID.idString)
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
            listOf(PrismTabsDynamicPluginListener::class.java.name to "com.intellij.ide.plugins.DynamicPluginListener"),
            registered,
        )
    }

    /**
     * The other half of an uninstall, and the half the user sees: once the plugin is going, it must
     * stop answering for tab colours, so that the repaint it asks for next leaves every tab the
     * colour the platform's own providers give it. Without this the strip keeps the colours of a
     * plugin that is no longer installed until the IDE is restarted — and a *disable* never prompts
     * for a restart at all.
     *
     * Both directions are asserted in one test on purpose. The gate is deliberately one-way (see
     * [TabGroupColorProvider.stop]), so "still colouring beforehand" and "no longer colouring
     * afterwards" cannot be separate tests without making them order-dependent on each other in a
     * shared JVM — exactly the flake that passes locally and fails in CI on a different shuffle.
     *
     * Driven through [PrismTabsDynamicPluginListener.stopColouringTabs] rather than the gate
     * directly: the gate closing itself is not the regression worth guarding, the unload forgetting
     * to close it is.
     */
    @Test
    fun `unload stops the provider handing out group colours`() {
        val group = TabGroup(id = "g", name = "Docs", lightRgb = 0x8AB4F8, darkRgb = 0x1A73E8)
        assertNotNull(
            TabGroupColorProvider.colorOf(group),
            "a loaded plugin must colour a tab whose file is in a group",
        )

        PrismTabsDynamicPluginListener().stopColouringTabs()

        assertNull(
            TabGroupColorProvider.colorOf(group),
            "an unloading plugin must hand back no colour, or the tab keeps it until a restart",
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

        PrismTabsDynamicPluginListener().releaseClipboard(clipboard)

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

        PrismTabsDynamicPluginListener().releaseClipboard(clipboard)

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

            PrismTabsDynamicPluginListener().releaseClipboard(clipboard)

            assertTrue(
                lostOwnership.await(10, TimeUnit.SECONDS),
                "the owner was never displaced, so a caret's Handler would still pin its text field",
            )
            assertEquals(text, clipboard.getContents(null).getTransferData(DataFlavor.stringFlavor))
        }
    }

    /**
     * The JDK behaviour [PrismTabsDynamicPluginListener.releaseClipboard] tells ownership by:
     * `SunClipboard` hands back its stored `contents` field while this JVM owns the clipboard, and
     * builds a fresh `ClipboardTransferable` per call when another application does. Pinned here
     * because the ownership test this replaced was also a reading of `SunClipboard`, was wrong, and
     * failed by doing nothing at all.
     *
     * Asserted on the *system* clipboard rather than the primary selection so that it runs on every
     * desktop this plugin ships to, not only the X11/Wayland one it was written on — the test below
     * covers the same behaviour on the sibling clipboard and skips everywhere else. That matters
     * because `SunClipboard` is abstract: what a Windows JVM actually owns is a `WClipboard` and a
     * macOS one a `CClipboard`, each with its own native transfer path, and neither is exercised by
     * the tests above — those construct a plain [Clipboard], which inherits none of that. Were the
     * identity to fail on one of them, [PrismTabsDynamicPluginListener.releaseClipboard] would return
     * early on that platform and release nothing, silently, exactly as the reading it replaced did.
     *
     * Confirmed live against `sun.awt.windows.WClipboard` on Windows 11 before this test existed.
     */
    @Test
    fun `the system clipboard reads back as one instance while this JVM owns it`() {
        val clipboard = systemClipboard()

        withContentsRestored(clipboard) {
            clipboard.setContents(StringSelection("prismtabs ownership probe"), null)

            assertSame(clipboard.getContents(null), clipboard.getContents(null))
        }
    }

    /**
     * The same ownership reading as the test above, on the clipboard whose owner
     * [PrismTabsDynamicPluginListener.releaseClipboard]'s second release step exists to displace.
     * Kept alongside rather than folded into it because the primary selection is a separate JDK
     * implementation — `WLClipboard`/`XClipboard` reached through `Toolkit.getSystemSelection`
     * rather than `getSystemClipboard` — and a platform is free to get one right and the other
     * wrong.
     *
     * Skipped where there is no primary selection to own: headless (CI), Windows and macOS.
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
     * The system clipboard, skipping the test — assumption recorded — where there is no desktop to
     * own one on. Guarded rather than caught: `getSystemClipboard` raises [java.awt.HeadlessException]
     * on a headless JVM, which is CI's, and a skip states that platform limit where a swallowed
     * exception would only hide it.
     */
    private fun systemClipboard(): Clipboard {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "no system clipboard: headless")
        return Toolkit.getDefaultToolkit().systemClipboard
    }

    /**
     * Puts the developer's own clipboard contents back after [body]. Both clipboards this exercises
     * are shared with every other window on the desktop, and a paste after a test run should still
     * produce what it produced before one; ownership cannot be handed back, but the contents can.
     *
     * Contents rather than *text*, because one of the two is the system clipboard: the primary
     * selection only ever holds text, but a developer's system clipboard routinely holds a
     * screenshot or a file list, and restoring a plain-text reading of one of those would quietly
     * destroy it on every `./gradlew test`.
     */
    private fun withContentsRestored(clipboard: Clipboard, body: () -> Unit) {
        val previous = snapshot(clipboard)
        try {
            body()
        } finally {
            clipboard.setContents(previous ?: StringSelection(""), null)
        }
    }

    /**
     * [clipboard]'s current contents read eagerly into memory, so that they can be published again
     * once a test has overwritten them — or null where there was nothing to save, in which case an
     * empty clipboard is the faithful restoration.
     *
     * Eagerly is the whole point: what [Clipboard.getContents] hands back for a clipboard another
     * application owns fetches its data from that application lazily, per flavour, and would read
     * back *this test's* contents if it were held and replayed instead of copied.
     *
     * Stream-valued flavours are dropped rather than saved. Their data is served as a one-shot
     * [java.io.InputStream] or [java.io.Reader], so reading one here to save it would consume it,
     * and what was saved could be published only once. Every flavour that carries a whole object —
     * [DataFlavor.stringFlavor], [DataFlavor.imageFlavor], [DataFlavor.javaFileListFlavor] and the
     * JVM-local reference forms — survives, which between them is everything a paste can actually
     * produce; the stream spellings that are dropped duplicate text kept via [DataFlavor.stringFlavor].
     */
    private fun snapshot(clipboard: Clipboard): Transferable? {
        val contents = clipboard.getContents(null) ?: return null
        val saved = contents.transferDataFlavors
            .filterNot { it.isStreamValued }
            .mapNotNull { flavor ->
                // Narrowly, and per flavour, for the reason the production listener catches that
                // way: a flavour this JDK cannot serve is one to skip, not to fail a test run over,
                // but an Error is nobody's to swallow.
                try {
                    flavor to contents.getTransferData(flavor)
                } catch (e: Exception) {
                    null
                }
            }
            .toMap()
        return if (saved.isEmpty()) null else SnapshotContents(saved)
    }

    /** Whether this flavour serves its data as a one-shot stream — see [snapshot]. */
    private val DataFlavor.isStreamValued: Boolean
        get() = isRepresentationClassInputStream ||
            isRepresentationClassReader ||
            isRepresentationClassCharBuffer ||
            isRepresentationClassByteBuffer

    /** A [snapshot]'s saved flavours, ready to go back on the clipboard as many times as needed. */
    private class SnapshotContents(private val saved: Map<DataFlavor, Any>) : Transferable {

        override fun getTransferDataFlavors(): Array<DataFlavor> = saved.keys.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in saved

        override fun getTransferData(flavor: DataFlavor): Any =
            saved[flavor] ?: throw UnsupportedFlavorException(flavor)
    }

    /**
     * A clipboard content with a flavour [PrismTabsDynamicPluginListener.releaseClipboard] must not
     * touch.
     */
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
