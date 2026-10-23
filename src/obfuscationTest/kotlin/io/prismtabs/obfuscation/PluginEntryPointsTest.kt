package io.prismtabs.obfuscation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The platform finds every PrismTabs class it instantiates by the name `plugin.xml` gives it, so a
 * renamed one stops the plugin, or that one feature, from loading. proguard-rules.pro keeps each by
 * hand; this fails when a class added to `plugin.xml` has no rule yet.
 */
class PluginEntryPointsTest {

    @Test
    fun `every class plugin xml names is in the jar under that name`() {
        val names = classNamesIn(shippedDescriptor())
        assertTrue(names.isNotEmpty(), "found no PrismTabs class names in plugin.xml, so nothing was checked")

        val missing = names.filterNot(::isLoadable)
        assertEquals(emptyList<String>(), missing, "plugin.xml names classes the obfuscated jar has renamed; keep them in proguard-rules.pro")
    }

    /** Guards the other tests here against passing because nothing was obfuscated at all. */
    @Test
    fun `classes plugin xml does not name are renamed`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("io.prismtabs.service.TabGroupVisibility", false, javaClass.classLoader)
        }
    }

    private fun shippedDescriptor(): Element = ObfuscatedJar.read { jar ->
        val entry = checkNotNull(jar.getJarEntry("META-INF/plugin.xml")) { "the obfuscated jar has no plugin.xml" }
        jar.getInputStream(entry).use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }
    }

    /**
     * Every attribute value and text node shaped like a PrismTabs class name: a package path, then
     * a capitalised simple name. Ids such as `io.prismtabs.settings.palette` are all lower case, so
     * they do not match, and new kinds of registration need no change here.
     */
    private fun classNamesIn(element: Element): Set<String> {
        val names = sortedSetOf<String>()
        fun visit(node: Node) {
            node.attributes?.let { attributes ->
                for (i in 0 until attributes.length) names += CLASS_NAME.findAll(attributes.item(i).nodeValue).map { it.value }
            }
            if (node.nodeType == Node.TEXT_NODE) node.nodeValue?.let { text -> names += CLASS_NAME.findAll(text).map { it.value } }
            for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i))
        }
        visit(element)
        return names
    }

    private fun isLoadable(name: String): Boolean = runCatching {
        ObfuscatedJar.assertLoadedFromIt(Class.forName(name, false, javaClass.classLoader))
    }.isSuccess

    private companion object {
        val CLASS_NAME = Regex("""\bio\.prismtabs(?:\.[a-z][a-z0-9]*)+\.[A-Z][A-Za-z0-9]*(?:\$[A-Za-z0-9]+)*\b""")
    }
}
