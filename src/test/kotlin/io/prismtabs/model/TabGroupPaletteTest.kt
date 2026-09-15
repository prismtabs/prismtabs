package io.prismtabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TabGroupPaletteTest {

    @Test
    fun `the palette offers Chrome's eight hues, in order`() {
        assertEquals(
            listOf("Grey", "Blue", "Red", "Yellow", "Green", "Pink", "Purple", "Cyan"),
            TabGroupPalette.PRESETS.map { it.label },
        )
        assertEquals(8, TabGroupPalette.PRESETS.map { it.id }.toSet().size, "every preset needs its own id")
    }

    /** Two presets sharing a colour in either theme would be two dots the user cannot tell apart. */
    @Test
    fun `no two presets share a colour in either theme`() {
        assertEquals(8, TabGroupPalette.PRESETS.map { it.light.rgb }.toSet().size, "light variants must all differ")
        assertEquals(8, TabGroupPalette.PRESETS.map { it.dark.rgb }.toSet().size, "dark variants must all differ")
    }
}
