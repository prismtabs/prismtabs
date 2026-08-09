package io.prismtabs.service

import io.prismtabs.model.TabGroupPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Plain JUnit 5, no platform harness — same reasoning as [TabGroupServiceTest]: nothing here
 * touches `Application`/`JBColor`, so it stays testable without an IDE.
 */
class TabGroupPaletteServiceTest {

    @Test
    fun `defaults to the built-in palette`() {
        val service = TabGroupPaletteService()
        assertEquals(TabGroupPalette.PRESETS, service.presets())
    }

    @Test
    fun `overriding one preset leaves the others at their default`() {
        val service = TabGroupPaletteService()
        val edited = TabGroupPalette.PRESETS.toMutableList()
        edited[0] = edited[0].copy(label = "Work", light = Color(0x111111), dark = Color(0x222222))

        service.setPresets(edited)

        assertEquals(edited, service.presets())
    }

    @Test
    fun `setting a preset back to its default value drops the override`() {
        val service = TabGroupPaletteService()
        val edited = TabGroupPalette.PRESETS.toMutableList()
        edited[0] = edited[0].copy(label = "Work")
        service.setPresets(edited)

        service.setPresets(TabGroupPalette.PRESETS)

        assertEquals(TabGroupPalette.PRESETS, service.presets())
        assertTrue(service.state.overrides.isEmpty())
    }

    @Test
    fun `resetToDefaults clears every override`() {
        val service = TabGroupPaletteService()
        service.setPresets(TabGroupPalette.PRESETS.map { it.copy(label = "Renamed") })

        service.resetToDefaults()

        assertEquals(TabGroupPalette.PRESETS, service.presets())
    }

    @Test
    fun `state round-trips through getState and loadState`() {
        val original = TabGroupPaletteService()
        val edited = TabGroupPalette.PRESETS.toMutableList()
        edited[2] = edited[2].copy(label = "Errors", light = Color(0xABCDEF), dark = Color(0x123456))
        original.setPresets(edited)

        val restored = TabGroupPaletteService()
        restored.loadState(original.state)

        assertEquals(edited, restored.presets())
    }

    @Test
    fun `only the changed preset is persisted in state, not the whole palette`() {
        val service = TabGroupPaletteService()
        val edited = TabGroupPalette.PRESETS.toMutableList()
        edited[0] = edited[0].copy(label = "Work")

        service.setPresets(edited)

        assertEquals(1, service.state.overrides.size)
        assertEquals(TabGroupPalette.PRESETS[0].id, service.state.overrides.single().id)
    }
}
