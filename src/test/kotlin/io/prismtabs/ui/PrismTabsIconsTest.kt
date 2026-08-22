package io.prismtabs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A misspelt or moved icon path is invisible at compile time and, at runtime, only shows up as a
 * blank cell in a toolbar nobody is watching — so the resources are asserted here instead. Plain
 * JUnit rather than a platform harness: `IconLoader` needs a running application, but what can
 * actually break is the resource being absent under the path the loader is given, and that is a
 * classpath question.
 */
class PrismTabsIconsTest {

    @Test
    fun `every icon the tool window paints is on the classpath, in both themes`() {
        for (name in listOf("showGroup", "showGroup_dark", "hideGroup", "hideGroup_dark")) {
            val svg = PrismTabsIcons::class.java.getResourceAsStream("/icons/$name.svg")
                ?.bufferedReader()?.readText()
            assertNotNull(svg, "/icons/$name.svg is missing from the plugin's resources")
            assertTrue(
                svg!!.contains("""viewBox="0 0 16 16""""),
                "$name must be drawn on the platform's 16x16 icon grid, got: $svg",
            )
        }
    }

    @Test
    fun `dark variants differ from their light siblings only in stroke colour`() {
        for (name in listOf("showGroup", "hideGroup")) {
            val light = PrismTabsIcons::class.java.getResource("/icons/$name.svg")!!.readText()
            val dark = PrismTabsIcons::class.java.getResource("/icons/${name}_dark.svg")!!.readText()
            assertTrue(light.contains("#6C707E"), "$name must use the platform's light-theme icon grey")
            assertTrue(dark.contains("#CED0D6"), "${name}_dark must use the platform's dark-theme icon grey")
            assertEquals(
                light.replace("#6C707E", "#CED0D6"),
                dark,
                "${name}_dark must be $name with only the stroke colour swapped, or the pair will drift",
            )
        }
    }
}
