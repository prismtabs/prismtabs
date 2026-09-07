package io.prismtabs.listener

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The two halves of [PrismTabsUnloadListener] that only `plugin.xml` can answer for, neither of
 * which fails loudly at runtime: a listener that is never registered, and a registered listener
 * that never recognises its own plugin. Either way the icon caches are not flushed, the class
 * loader survives, and the only symptom is an "unable to unload" dialog on somebody else's machine
 * weeks later — exactly the failure this listener exists to prevent.
 *
 * Reads `src/main/resources`' own copy off the test classpath, like [
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
}
